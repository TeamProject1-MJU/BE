package com.mju.linkro.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
// JPA slice tests may need an explicit @Import(JpaAuditingConfig.class).
public class JpaAuditingConfig {}
