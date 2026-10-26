package dev.sami.platform.dto;

/** Result of a successful cost import: how many rows were read and what happened to them. */
public record CostImportResponse(int rowsRead, int created, int updated, int unchanged) {}
