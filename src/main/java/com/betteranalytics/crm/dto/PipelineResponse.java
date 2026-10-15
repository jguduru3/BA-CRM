package com.betteranalytics.crm.dto;

import com.betteranalytics.crm.entity.Lead;
import java.util.List;
import java.util.Map;

public record PipelineResponse(String groupBy, Map<String, List<Lead>> columns) {}
