package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {
    List<TeamMember> findByTeamId(Long teamId);

    // NOTE: team_members has no organization_id column of its own — tenant
    // scoping for team membership is enforced by the controller checking the
    // parent Team's organizationId first, then operating on members by teamId.
    List<TeamMember> findByUserId(Long userId); // for the permission engine: "what team(s) is this user on"
    Optional<TeamMember> findByTeamIdAndUserId(Long teamId, Long userId);

    // Batch roster fetch — lets PermissionService / TeamController resolve
    // several teams in one query instead of one query per team.
    List<TeamMember> findByTeamIdIn(Collection<Long> teamIds);
}
