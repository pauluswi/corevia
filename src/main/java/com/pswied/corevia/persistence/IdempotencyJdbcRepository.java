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
    public Optional<IdempotencyRecord> claimOrGetExisting(String key, String fingerprint, String transactionId, TransactionState status) {
        String insertSql = "INSERT INTO idempotency_record (idempotency_key, request_fingerprint, transaction_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
        Object[] params = new Object[] { key, fingerprint, transactionId, status.name(), Instant.now(), Instant.now() };
        try {
            int updated = jdbc.update(insertSql, params);
            if (updated > 0) {
                // inserted successfully, fetch via JPA to map fully
                return jpaRepo.findByIdempotencyKey(key);
            }
        } catch (DataAccessException ex) {
            // If the insert failed due to unique constraint, fall through to read existing
            // Other DB errors will also be caught here; return existing if present
        }
        return jpaRepo.findByIdempotencyKey(key);
    }
}
