package com.shashank.invoicekit.invoice.application.service;

import com.shashank.invoicekit.invoice.domain.entity.AppliedRates;

/**
 * The tax worked out for one line.
 *
 * @param hsnCode SAC code from the line's tax code
 * @param rates rates charged after the supply type's split
 * @param amounts rounded amounts
 */
record LineTax(String hsnCode, AppliedRates rates, TaxAmounts amounts) {}
