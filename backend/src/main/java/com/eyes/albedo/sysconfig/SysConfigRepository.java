package com.eyes.albedo.sysconfig;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 平台级配置仓储（platform scope，无租户条件）。
 */
public interface SysConfigRepository extends JpaRepository<SysConfig, Long> {

    Optional<SysConfig> findByConfigGroupAndConfigKey(String configGroup, String configKey);

    List<SysConfig> findByIsFrontendOrderByConfigGroupAscSortOrderAscConfigKeyAsc(Integer isFrontend);

    List<SysConfig> findByIsFrontendAndConfigGroupOrderBySortOrderAscConfigKeyAsc(Integer isFrontend,
                                                                                 String configGroup);
}
