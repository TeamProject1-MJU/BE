package com.mju.linkro.common.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.data.domain.Persistable;

/** UUID-only foundation; does not impose timestamps or soft deletion on an entity. */
@MappedSuperclass
public abstract class BaseUuidEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UuidCreator.getTimeOrderedEpoch();

    @Transient
    private boolean newEntity = true;

    protected BaseUuidEntity() {}

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    /** Compares the concrete mapped entity type and its immutable, preassigned UUID. */
    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BaseUuidEntity entity) || persistentClass(this) != persistentClass(entity)) {
            return false;
        }
        UUID identifier = identifier(this);
        return identifier != null && identifier.equals(identifier(entity));
    }

    @Override
    public final int hashCode() {
        return identifier(this).hashCode();
    }

    // Read proxy metadata rather than its constructor-generated fields or initializing it.
    private static Class<?> persistentClass(BaseUuidEntity entity) {
        return entity instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass() : entity.getClass();
    }

    private static UUID identifier(BaseUuidEntity entity) {
        return entity instanceof HibernateProxy proxy
                ? (UUID) proxy.getHibernateLazyInitializer().getInternalIdentifier() : entity.id;
    }

    @PostPersist
    @PostLoad
    private void markPersisted() {
        newEntity = false;
    }
}
