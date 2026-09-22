package com.pswied.corevia.application;

import com.pswied.corevia.domain.IdempotencyRecord;
import com.pswied.corevia.domain.TransactionState;
import com.pswied.corevia.persistence.IdempotencyRecordRepository;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pswied.corevia.persistence.IdempotencyJdbcRepository;

@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final IdempotencyQueryService queryService;
    private final EntityManager em;
    private IdempotencyJdbcRepository jdbcRepo; // optional, injected when available

    @org.springframework.beans.factory.annotation.Autowired
    public IdempotencyService(IdempotencyRecordRepository repository, IdempotencyQueryService queryService, EntityManager em) {
        this.repository = repository;
        this.queryService = queryService;
        this.em = em;
    }

    // Convenience constructor for tests and environments without EntityManager
    public IdempotencyService(IdempotencyRecordRepository repository, IdempotencyQueryService queryService) {
        this(repository, queryService, null);
    }

    // optional setter injection so DataJpaTest (which doesn't load jdbc beans) keeps working
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJdbcRepo(IdempotencyJdbcRepository jdbcRepo) {
        this.jdbcRepo = jdbcRepo;
    }

    /**
     * Try to claim an idempotency key by inserting a record. If another request already created
     * the record, return the existing one. Prefer a DB-level upsert via JDBC if available to avoid
     * Hibernate session issues on constraint violation.
     */
    @Transactional
    public Optional<IdempotencyRecord> claimOrGetExisting(String idempotencyKey, String requestFingerprint, String transactionId, TransactionState initialStatus) {
        // fast path: check existing
        Optional<IdempotencyRecord> existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing;
        }

        // If JDBC upsert repository is available, prefer it (avoids JPA insert-then-catch pattern)
        if (jdbcRepo != null) {
            Optional<IdempotencyRecord> claimed = jdbcRepo.claimOrGetExisting(idempotencyKey, requestFingerprint, transactionId, initialStatus);
            if (claimed.isPresent()) {
                return claimed;
            }
            // otherwise fall through to JPA save as a last resort
        }

        try {
            IdempotencyRecord record = new IdempotencyRecord(idempotencyKey, requestFingerprint, transactionId, initialStatus);
            IdempotencyRecord saved = repository.save(record);
            return Optional.of(saved);
        } catch (DataIntegrityViolationException ex) {
            // clear persistence context after an insert failure to avoid Hibernate assertion about null id
            try {
                if (em != null) {
                    em.clear();
                }
            } catch (Exception ignore) {
            }
            return queryService.findExisting(idempotencyKey);
        }
    }

    @Transactional
    public void updateStatus(String idempotencyKey, TransactionState status) {
        Optional<IdempotencyRecord> rec = repository.findByIdempotencyKey(idempotencyKey);
        rec.ifPresent(r -> {
            try {
                r.setStatus(status);
                repository.save(r);
            } catch (Exception e) {
                // best-effort update
            }
        });
    }
}
