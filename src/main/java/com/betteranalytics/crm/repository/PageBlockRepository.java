package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.PageBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PageBlockRepository extends JpaRepository<PageBlock, Long> {
    List<PageBlock> findByPageIdOrderByPosition(Long pageId);
    void deleteByPageId(Long pageId);
}
