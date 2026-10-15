package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.WhiteboardBoard;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WhiteboardBoardRepository extends JpaRepository<WhiteboardBoard, Long> {
    List<WhiteboardBoard> findByOrganizationIdOrderByUpdatedAtDesc(Long organizationId);
    Optional<WhiteboardBoard> findByIdAndOrganizationId(Long id, Long organizationId);
}
