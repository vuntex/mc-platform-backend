package com.mcplatform.domain.report;

/**
 * The fixed, backend-validated set of report categories. A report is an accusation; the category only
 * classifies the alleged misconduct. Display labels (German) live in the plugin menu — these constants
 * are the canonical wire/persistence values.
 */
public enum ReportCategory {
    CHEATING,
    BELEIDIGUNG,
    SPAM_WERBUNG,
    TEAMING_BUG_ABUSE,
    SONSTIGES
}
