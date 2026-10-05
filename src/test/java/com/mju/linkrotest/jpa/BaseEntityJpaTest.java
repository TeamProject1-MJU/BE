package com.mju.linkrotest.jpa;

import com.mju.linkro.common.config.JpaAuditingConfig;
import com.mju.linkro.common.entity.BaseTimeEntity;
import com.mju.linkro.common.entity.BaseUuidEntity;
import com.mju.linkro.common.entity.BaseUuidTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixtures live outside the application's scan so they never alter its domain schema. */
@SpringBootTest(classes = BaseEntityJpaTest.TestConfig.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:base-entity;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.show-sql=false"
}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class BaseEntityJpaTest {

    @Autowired private UuidRepository uuidRepository;
    @Autowired private TimeRepository timeRepository;
    @Autowired private UuidTimeRepository uuidTimeRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private AuditingHandler auditingHandler;

    @Test
    void equalityUsesSameConcreteEntityTypeAndUuid() {
        var entity = new UuidRecord("one");
        var sameId = new UuidRecord("two");
        var differentId = new UuidRecord("three");
        var differentType = new UuidTimeRecord("four");
        ReflectionTestUtils.setField(sameId, "id", entity.getId());
        ReflectionTestUtils.setField(differentType, "id", entity.getId());

        assertThat(entity.equals(entity)).isTrue();
        assertThat(entity.equals(sameId)).isTrue();
        assertThat(sameId.equals(entity)).isTrue();
        assertThat(sameId.hashCode()).isEqualTo(entity.hashCode());
        assertThat(entity.equals(differentId)).isFalse();
        assertThat(entity.equals(differentType)).isFalse();
        assertThat(differentType.equals(entity)).isFalse();
        assertThat(entity.equals(null)).isFalse();
        assertThat(entity.equals(entity.getId())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void equalityAndHashRemainStableThroughPersistLoadAndDetachedProxy(boolean withTime) {
        BaseUuidEntity entity = withTime ? new UuidTimeRecord("original") : new UuidRecord("original");
        int hash = entity.hashCode();
        var set = new java.util.HashSet<BaseUuidEntity>();
        var map = new java.util.HashMap<BaseUuidEntity, String>();
        set.add(entity);
        map.put(entity, "value");
        if (withTime) {
            uuidTimeRepository.saveAndFlush((UuidTimeRecord) entity);
        } else {
            uuidRepository.saveAndFlush((UuidRecord) entity);
        }
        assertThat(entity.hashCode()).isEqualTo(hash);
        assertThat(set.contains(entity)).isTrue();
        assertThat(map.get(entity)).isEqualTo("value");
        entityManager.clear();

        BaseUuidEntity loaded = withTime ? uuidTimeRepository.findById(entity.getId()).orElseThrow()
                : uuidRepository.findById(entity.getId()).orElseThrow();
        assertThat(entity.equals(loaded)).isTrue();
        assertThat(loaded.equals(entity)).isTrue();
        assertThat(loaded.hashCode()).isEqualTo(hash);
        assertThat(set.contains(loaded)).isTrue();
        assertThat(map.get(loaded)).isEqualTo("value");
        entityManager.clear();

        BaseUuidEntity proxy = withTime ? uuidTimeRepository.getReferenceById(entity.getId())
                : uuidRepository.getReferenceById(entity.getId());
        assertThat(proxy).isInstanceOf(org.hibernate.proxy.HibernateProxy.class);
        assertThat(org.hibernate.Hibernate.isInitialized(proxy)).isFalse();
        entityManager.clear();
        assertThat(proxy.equals(proxy)).isTrue();
        assertThat(entity.equals(proxy)).isTrue();
        assertThat(proxy.equals(entity)).isTrue();
        assertThat(loaded.equals(proxy)).isTrue();
        assertThat(proxy.equals(loaded)).isTrue();
        assertThat(proxy.hashCode()).isEqualTo(hash);
        assertThat(set.contains(proxy)).isTrue();
        assertThat(map.get(proxy)).isEqualTo("value");
        assertThat(org.hibernate.Hibernate.isInitialized(proxy)).isFalse();
    }

    @Test
    void newUuidEntityHasVersionSevenAndIndependentNewState() {
        var entity = new UuidRecord("new");
        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getId().version()).isEqualTo(7);
        assertThat(entity.isNew()).isTrue();
        assertThat(new UuidRecord("other").getId()).isNotEqualTo(entity.getId());
    }

    @Test
    void repositoryPersistsWithoutSelectAndMarksPersistedAndLoadedEntitiesExisting() {
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var entity = new UuidRecord("original");
        UUID id = entity.getId();

        assertThat(uuidRepository.saveAndFlush(entity)).isSameAs(entity);
        assertThat(entity.isNew()).isFalse();
        assertThat(statistics.getEntityInsertCount()).isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);

        entityManager.clear();
        var loaded = uuidRepository.findById(id).orElseThrow();
        assertThat(loaded).isNotSameAs(entity);
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.isNew()).isFalse();
        loaded.label = "modified";
        uuidRepository.saveAndFlush(loaded);
        entityManager.clear();
        assertThat(uuidRepository.findById(id).orElseThrow().label).isEqualTo("modified");
        assertThat(statistics.getEntityInsertCount()).isEqualTo(1);
        assertThat(statistics.getEntityUpdateCount()).isEqualTo(1);
    }

    @Test
    void auditingPersistsBothTimesAndUpdatesOnlyUpdatedAtWithBigintId() {
        Instant created = Instant.parse("2026-10-05T00:00:00Z");
        Instant modified = created.plusSeconds(60);
        auditingHandler.setDateTimeProvider(() -> Optional.of(created));
        try {
            var entity = timeRepository.saveAndFlush(new TimeRecord("original"));
            assertThat(entity.getCreatedAt()).isEqualTo(created);
            assertThat(entity.getUpdatedAt()).isEqualTo(created);
            entityManager.clear();

            var loaded = timeRepository.findById(entity.id).orElseThrow();
            assertThat(loaded.getCreatedAt()).isEqualTo(created);
            assertThat(loaded.getUpdatedAt()).isEqualTo(created);
            auditingHandler.setDateTimeProvider(() -> Optional.of(modified));
            loaded.label = "modified";
            timeRepository.saveAndFlush(loaded);
            entityManager.clear();

            var updated = timeRepository.findById(entity.id).orElseThrow();
            assertThat(updated.getCreatedAt()).isEqualTo(created);
            assertThat(updated.getUpdatedAt()).isEqualTo(modified);
        } finally {
            auditingHandler.setDateTimeProvider(org.springframework.data.auditing.CurrentDateTimeProvider.INSTANCE);
        }
    }

    @Test
    void combinedFoundationPreservesUuidLifecycleAndAuditingWithoutSelectOnInsert() {
        Instant created = Instant.parse("2026-10-05T00:00:00Z");
        Instant modified = created.plusSeconds(60);
        auditingHandler.setDateTimeProvider(() -> Optional.of(created));
        try {
            var entity = new UuidTimeRecord("original");
            UUID id = entity.getId();
            assertThat(id).isNotNull();
            assertThat(id.version()).isEqualTo(7);
            assertThat(entity.isNew()).isTrue();
            var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
            statistics.clear();

            assertThat(uuidTimeRepository.saveAndFlush(entity)).isSameAs(entity);
            assertThat(entity.isNew()).isFalse();
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
            assertThat(statistics.getEntityInsertCount()).isEqualTo(1);
            assertThat(entity.getCreatedAt()).isEqualTo(created);
            assertThat(entity.getUpdatedAt()).isEqualTo(created);
            entityManager.clear();

            var loaded = uuidTimeRepository.findById(id).orElseThrow();
            assertThat(loaded).isNotSameAs(entity);
            assertThat(loaded.getId()).isEqualTo(id);
            assertThat(loaded.isNew()).isFalse();
            assertThat(loaded.getCreatedAt()).isEqualTo(created);
            assertThat(loaded.getUpdatedAt()).isEqualTo(created);
            auditingHandler.setDateTimeProvider(() -> Optional.of(modified));
            loaded.label = "modified";
            uuidTimeRepository.saveAndFlush(loaded);
            entityManager.clear();

            var updated = uuidTimeRepository.findById(id).orElseThrow();
            assertThat(updated.label).isEqualTo("modified");
            assertThat(updated.isNew()).isFalse();
            assertThat(updated.getCreatedAt()).isEqualTo(created);
            assertThat(updated.getUpdatedAt()).isEqualTo(modified);
            assertThat(statistics.getEntityInsertCount()).isEqualTo(1);
            assertThat(statistics.getEntityUpdateCount()).isEqualTo(1);
        } finally {
            auditingHandler.setDateTimeProvider(org.springframework.data.auditing.CurrentDateTimeProvider.INSTANCE);
        }
    }

    @Test
    void timestampColumnsAreOnlyPresentInOptInTables() {
        var session = entityManager.unwrap(org.hibernate.Session.class);
        var uuidColumns = session.createNativeQuery(
                "select column_name from information_schema.columns where table_name = 'TEST_UUID_RECORD'",
                String.class).getResultList();
        assertThat(uuidColumns).containsExactlyInAnyOrder("ID", "LABEL");
        var timeColumns = session.createNativeQuery(
                "select column_name from information_schema.columns where table_name = 'TEST_TIME_RECORD'",
                String.class).getResultList();
        var combinedColumns = session.createNativeQuery(
                "select column_name from information_schema.columns where table_name = 'TEST_UUID_TIME_RECORD'",
                String.class).getResultList();
        assertThat(timeColumns).containsExactlyInAnyOrder("ID", "LABEL", "CREATED_AT", "UPDATED_AT");
        assertThat(combinedColumns).containsExactlyInAnyOrder("ID", "LABEL", "CREATED_AT", "UPDATED_AT");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BaseEntityJpaTest.class)
    @EnableJpaRepositories(basePackageClasses = BaseEntityJpaTest.class, considerNestedRepositories = true)
    @Import(JpaAuditingConfig.class)
    static class TestConfig {}

    interface UuidRepository extends JpaRepository<UuidRecord, UUID> {}
    interface TimeRepository extends JpaRepository<TimeRecord, Long> {}
    interface UuidTimeRepository extends JpaRepository<UuidTimeRecord, UUID> {}

    @Entity
    @Table(name = "test_uuid_time_record")
    public static class UuidTimeRecord extends BaseUuidTimeEntity {
        @Column(nullable = false)
        private String label;

        protected UuidTimeRecord() {}

        UuidTimeRecord(String label) { this.label = label; }
    }

    @Entity
    @Table(name = "test_uuid_record")
    public static class UuidRecord extends BaseUuidEntity {
        @Column(nullable = false)
        private String label;

        protected UuidRecord() {}

        UuidRecord(String label) { this.label = label; }
    }

    @Entity
    @Table(name = "test_time_record")
    public static class TimeRecord extends BaseTimeEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false)
        private String label;

        protected TimeRecord() {}

        TimeRecord(String label) { this.label = label; }
    }
}
