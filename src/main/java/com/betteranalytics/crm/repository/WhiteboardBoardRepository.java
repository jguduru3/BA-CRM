package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.WhiteboardBoard;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhiteboardBoardRepository extends JpaRepository<WhiteboardBoard, Long> {
    List<WhiteboardBoard> findByOrganizationIdOrderByUpdatedAtDesc(Long organizationId);
    List<WhiteboardBoard> findByOrganizationId(Long organizationId, Pageable pageable);
    Optional<WhiteboardBoard> findByIdAndOrganizationId(Long id, Long organizationId);
}
