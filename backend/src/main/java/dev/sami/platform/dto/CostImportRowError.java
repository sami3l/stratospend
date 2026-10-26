package dev.sami.platform.dto;

/**
 * One problem in an imported cost file. Row numbers match a spreadsheet, so the header is row 1.
 * The column is null when the problem concerns the whole row or file.
 */
public record CostImportRowError(int row, String column, String message) {}
