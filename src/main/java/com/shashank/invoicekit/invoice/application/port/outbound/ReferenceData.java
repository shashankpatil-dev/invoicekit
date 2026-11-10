package com.shashank.invoicekit.invoice.application.port.outbound;

import com.shashank.invoicekit.invoice.domain.entity.AdapterConfig;
import com.shashank.invoicekit.invoice.domain.entity.BusinessUnitEntity;
import com.shashank.invoicekit.invoice.domain.entity.Declaration;
import com.shashank.invoicekit.invoice.domain.entity.GstState;
import com.shashank.invoicekit.invoice.domain.entity.LegalEntity;
import com.shashank.invoicekit.invoice.domain.entity.TaxRate;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The platform-owned data intake reads to turn an event into a payload: entities, which BU may use
 * which entity, adapter configs, GST states, tax rates and declarations. It changes rarely, so an
 * implementation may cache it.
 */
public interface ReferenceData {

  /** The entity with this code, or nothing when there is none. */
  Optional<LegalEntity> entity(String entityCode);

  /** The {@code bu_entity} row that lets a BU invoice from an entity, or nothing if it may not. */
  Optional<BusinessUnitEntity> businessUnitEntity(String businessUnit, String entityCode);

  /** The BU's active adapter config, or nothing when it has none. */
  Optional<AdapterConfig> activeConfig(String businessUnit);

  /** The GST state with this two-digit code, or nothing for an unknown code. */
  Optional<GstState> gstState(String stateCode);

  /** The rate a tax code has on a date, or nothing when the code has no rate that day. */
  Optional<TaxRate> taxRate(String taxCode, LocalDate date);

  /** Every declaration, current or not; each one decides for itself whether it applies. */
  List<Declaration> declarations();

  /** Every BU's active adapter config, for the startup check. */
  List<AdapterConfig> activeConfigs();

  /** Every {@code bu_entity} row, for the startup check. */
  List<BusinessUnitEntity> businessUnitEntities();
}
