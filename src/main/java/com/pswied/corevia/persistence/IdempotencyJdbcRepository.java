package com.pswied.corevia.persistence;

import com.pswied.corevia.domain.IdempotencyRecord;
import com.pswied.corevia.domain.TransactionState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyJdbcRepository {

    private final JdbcTemplate jdbc;
    private final IdempotencyRecordRepository jpaRepo;

    public IdempotencyJdbcRepository(JdbcTemplate jdbc, IdempotencyRecordRepository jpaRepo) {
        this.jdbc = jdbc;
        this.jpaRepo = jpaRepo;
    }

    /**
     * Attempt to insert an idempotency record using JDBC. If insert affects 1 row, return the saved record.
     * If insert affects 0 rows (conflict), fall back to reading existing record via JPA repo.
     * This keeps the atomicity on the DB side while avoiding mixing failed JPA insert attempts in the same Hibernate session.
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Optional<IdempotencyRecord> claimOrGetExisting(String key, String fingerprint, String transactionId, TransactionState status) {
        // Try Postgres-specific RETURNING upsert when possible
        String dbName = null;
        try (java.sql.Connection conn = jdbc.getDataSource().getConnection()) {
            dbName = conn.getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            // ignore and fall back to generic path
        }

        Object[] params = new Object[] { key, fingerprint, transactionId, status.name(), Instant.now(), Instant.now() };

        if (dbName != null && dbName.toLowerCase().contains("postgres")) {
            String pgSql = "INSERT INTO idempotency_record (idempotency_key, request_fingerprint, transaction_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING RETURNING idempotency_key, request_fingerprint, transaction_id, status, created_at, updated_at, expires_at";
            try {
                java.util.List<IdempotencyRecord> rows = jdbc.query(pgSql, params, new RowMapper<IdempotencyRecord>() {
                    @Override
                    public IdempotencyRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
                        String k = rs.getString("idempotency_key");
                        String fp = rs.getString("request_fingerprint");
                        String tx = rs.getString("transaction_id");
                        String st = rs.getString("status");
                        java.sql.Timestamp createdTs = rs.getTimestamp("created_at");
                        java.sql.Timestamp updatedTs = rs.getTimestamp("updated_at");
                        java.sql.Timestamp expiresTs = null;
                        try {
                            expiresTs = rs.getTimestamp("expires_at");
                        } catch (SQLException ignore) {
                        }
                        java.time.Instant created = createdTs != null ? createdTs.toInstant() : Instant.now();
                        java.time.Instant updated = updatedTs != null ? updatedTs.toInstant() : created;
                        java.time.Instant expires = expiresTs != null ? expiresTs.toInstant() : null;
                        TransactionState tsEnum = TransactionState.valueOf(st);
                        return new IdempotencyRecord(k, fp, tx, tsEnum, created, updated, expires);
                    }
                });
                if (!rows.isEmpty()) {
                    return Optional.of(rows.get(0));
                }
            } catch (DataAccessException ex) {
                // fall through to generic fallback
            }
            // If RETURNING returned nothing (conflict) or failed, fetch existing via JPA
            return jpaRepo.findByIdempotencyKey(key);
        }

        // Generic fallback: for H2, use INSERT ... SELECT ... WHERE NOT EXISTS pattern to avoid updating existing rows.
        if (dbName != null && dbName.toLowerCase().contains("h2")) {
            String insertIfNotExists = "INSERT INTO idempotency_record (idempotency_key, request_fingerprint, transaction_id, status, created_at, updated_at) SELECT ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM idempotency_record WHERE idempotency_key = ?)";
            try {
                jdbc.update(insertIfNotExists, new Object[] { key, fingerprint, transactionId, status.name(), params[4], params[5], key });
                return jpaRepo.findByIdempotencyKey(key);
            } catch (DataAccessException ex) {
                // fall through to simple insert/read
            }
        }

        String insertSql = "INSERT INTO idempotency_record (idempotency_key, request_fingerprint, transaction_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
        try {
            int updated = jdbc.update(insertSql, params);
            if (updated > 0) {
                return jpaRepo.findByIdempotencyKey(key);
            }
        } catch (DataAccessException ex) {
            // ignore and fall back to read
        }
        return jpaRepo.findByIdempotencyKey(key);
    }
}

