package com.pswied.corevia.application;

import com.pswied.corevia.domain.IdempotencyRecord;
import com.pswied.corevia.persistence.IdempotencyRecordRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyQueryService {

    private final IdempotencyRecordRepository repository;

    public IdempotencyQueryService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> findExisting(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey);
    }
}
