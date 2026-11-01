// Invoice template for the subscriptions BU.
// The renderer passes the saved payload as JSON. It is only read as data, never run as template code.
#let data = json(bytes(sys.inputs.payload))
#let supplier = data.supplier
#let recipient = data.recipient
#let totals = data.totals
#let attributes = data.at("attributes", default: (:))
#let intra-state = data.supplyType == "INTRA_STATE"

// Colours used across the invoice.
#let ink = rgb("#14242f")
#let muted = rgb("#5b6d75")
#let accent = rgb("#0a5c63")
#let accent-tint = rgb("#e4f0f1")
#let hairline = rgb("#d3dde1")
#let panel = rgb("#f4f7f8")

// Groups digits the Indian way, for example 1234567 becomes 12,34,567.
#let group-indian(digits) = {
  if digits.len() <= 3 { return digits }
  let tail = digits.slice(-3)
  let head = digits.slice(0, -3)
  let groups = ()
  while head.len() > 2 {
    groups.insert(0, head.slice(-2))
    head = head.slice(0, -2)
  }
  if head.len() > 0 { groups.insert(0, head) }
  groups.join(",") + "," + tail
}

// Formats a "1234.50" amount as ₹1,234.50.
#let money(value) = {
  let parts = value.split(".")
  "₹" + group-indian(parts.at(0)) + "." + parts.at(1)
}

// Formats a unit price, keeping extra decimals for small usage rates like ₹0.0025.
#let unit-money(value) = {
  let parts = value.split(".")
  let decimals = parts.at(1).trim("0", at: end)
  if decimals.len() < 2 { decimals = parts.at(1).slice(0, 2) }
  money(parts.at(0) + "." + decimals)
}

// Formats a tax rate, so "9.00" shows as 9%.
#let rate(value) = {
  let parts = value.split(".")
  if parts.at(1).trim("0") == "" { parts.at(0) + "%" } else { value + "%" }
}

// Formats a "2026-11-01" date as 01 Nov 2026.
#let date(value) = {
  let parts = value.split("-").map(int)
  datetime(year: parts.at(0), month: parts.at(1), day: parts.at(2)).display("[day] [month repr:short] [year]")
}

// Reads a field that may be missing, and returns none instead of failing.
#let optional(source, key) = source.at(key, default: none)

// Small uppercase heading used above each section.
#let label(body) = text(size: 7pt, weight: 600, fill: muted, tracking: 0.6pt, upper(body))

// One "name ... value" row. It is skipped when the value is missing.
#let field(name, value) = if value != none {
  block(above: 3.5pt, below: 0pt, grid(
    columns: (auto, 1fr),
    column-gutter: 10pt,
    text(fill: muted, name),
    align(right, value),
  ))
}

// One label and value, used in the summary strip.
#let stat(name, value, fill: ink) = {
  label(name)
  v(2pt)
  text(size: 11pt, weight: 700, fill: fill, value)
}

// Page setup. Every page footer shows the supplier, invoice number and page count.
#set document(title: "Tax Invoice " + data.invoiceNumber)
#set text(font: "Source Sans 3", size: 9.5pt, fill: ink, number-width: "tabular")
#set par(leading: 0.5em, spacing: 0.5em)
#set page(
  paper: "a4",
  margin: (x: 15mm, top: 14mm, bottom: 17mm),
  footer: context {
    set text(size: 7.5pt, fill: muted)
    line(length: 100%, stroke: 0.5pt + hairline)
    v(2pt)
    grid(
      columns: (1fr, auto, auto),
      column-gutter: 14pt,
      supplier.legalName,
      data.invoiceNumber,
      [Page #counter(page).display() of #counter(page).final().first()],
    )
  },
)

// Header: supplier details on the left, document title on the right.
#grid(
  columns: (1fr, auto),
  column-gutter: 16pt,
  {
    text(size: 15pt, weight: 700, supplier.legalName)
    v(1pt)
    set text(size: 9pt, fill: muted)
    if optional(supplier, "tradeName") != none [#supplier.tradeName \ ]
    [#supplier.address \ ]
    [#supplier.stateName (#supplier.stateCode) \ ]
    text(fill: ink)[GSTIN *#supplier.gstin*]
  },
  align(right, {
    text(size: 24pt, weight: 700, fill: accent, "Tax Invoice")
    v(1pt)
    text(size: 8pt, fill: muted, "Original for Recipient")
  }),
)

#v(10pt)

// Summary strip: the four facts a reader looks for first.
#block(width: 100%, fill: accent-tint, radius: 2pt, inset: (x: 12pt, y: 9pt), grid(
  columns: (1.3fr, 1fr, 1fr, auto),
  column-gutter: 12pt,
  stat("Invoice number", data.invoiceNumber),
  stat("Invoice date", date(data.invoiceDate)),
  stat("Due date", if optional(data, "dueDate") != none { date(data.dueDate) } else { "On receipt" }),
  align(right, stat("Amount due", money(totals.totalValue), fill: accent)),
))

#v(10pt)

// Recipient on the left, supply details on the right.
#grid(
  columns: (1fr, 1fr),
  column-gutter: 24pt,
  {
    label("Billed to")
    v(4pt)
    text(size: 10.5pt, weight: 600, recipient.name)
    v(1pt)
    if optional(recipient, "address") != none [#recipient.address \ ]
    if optional(recipient, "stateName") != none [#recipient.stateName (#recipient.stateCode) \ ]
    if optional(recipient, "gstin") != none [GSTIN *#recipient.gstin*]
  },
  {
    label("Supply details")
    v(1pt)
    field("Place of supply", [#data.placeOfSupply.stateName (#data.placeOfSupply.stateCode)])
    field("Tax payable on reverse charge", if data.reverseCharge { "Yes" } else { "No" })
    field("Payment terms", optional(data, "paymentTerms"))
    field("Plan", optional(attributes, "planName"))
    field("Subscription", optional(attributes, "subscriptionId"))
  },
)

#v(12pt)

#let head(body) = text(size: 7.5pt, weight: 600, fill: white, body)

// Intra-state invoices show CGST and SGST. Inter-state invoices show IGST.
#let tax-columns = if intra-state { (auto, auto) } else { (auto,) }
#let tax-headers = if intra-state {
  (align(right, head[CGST]), align(right, head[SGST]))
} else {
  (align(right, head[IGST]),)
}

// A tax amount with its rate underneath.
#let tax-cell(amount, percent) = align(right)[#money(amount) \ #text(size: 7.5pt, fill: muted, rate(percent))]

#let tax-cells(item) = if intra-state {
  (tax-cell(item.cgstAmount, item.cgstRate), tax-cell(item.sgstAmount, item.sgstRate))
} else {
  (tax-cell(item.igstAmount, item.igstRate),)
}

// Line description, with the service period underneath when there is one.
#let description(item) = {
  text(weight: 600, item.description)
  let start = optional(item, "servicePeriodStart")
  let end = optional(item, "servicePeriodEnd")
  if start != none and end != none {
    linebreak()
    text(size: 8pt, fill: muted, date(start) + " to " + date(end))
  }
}

// Line items. The table header repeats on every page of a long invoice.
#table(
  columns: (auto, 1fr, auto, auto, auto, auto, auto, ..tax-columns, auto),
  stroke: (x, y) => if y > 0 { (bottom: 0.5pt + hairline) },
  inset: (x: 5pt, y: 6pt),
  fill: (x, y) => if y == 0 { accent },
  table.header(
    head[\#],
    head[Description],
    head[SAC],
    align(right, head[Qty]),
    align(right, head[Rate]),
    align(right, head[Discount]),
    align(right, head[Taxable]),
    ..tax-headers,
    align(right, head[Total]),
  ),
  ..data.lineItems
    .map(item => (
      text(fill: muted, str(item.lineNo)),
      description(item),
      item.hsnCode,
      align(right)[#item.quantity \ #text(size: 7.5pt, fill: muted, item.unit)],
      align(right, unit-money(item.unitPrice)),
      align(right, money(item.discount)),
      align(right, money(item.taxableValue)),
      ..tax-cells(item),
      align(right, strong(money(item.lineTotal))),
    ))
    .flatten(),
)

#v(10pt)

// Payment details and amount in words on the left, totals on the right.
#block(breakable: false, grid(
  columns: (1fr, 205pt),
  column-gutter: 24pt,
  {
    label("Amount in words")
    v(3pt)
    text(weight: 600, totals.amountInWords)
    if optional(supplier, "bankDetails") != none {
      let bank = supplier.bankDetails
      v(9pt)
      label("Pay to")
      v(1pt)
      field("Account name", bank.accountName)
      field("Account number", bank.accountNumber)
      field("IFSC", bank.ifsc)
    }
  },
  block(width: 100%, fill: panel, radius: 2pt, inset: 10pt, {
    field("Taxable value", money(totals.totalTaxableValue))
    if intra-state {
      field("CGST", money(totals.totalCgst))
      field("SGST", money(totals.totalSgst))
    } else {
      field("IGST", money(totals.totalIgst))
    }
    if totals.totalCess != "0.00" { field("Cess", money(totals.totalCess)) }
    v(6pt)
    line(length: 100%, stroke: 0.75pt + accent)
    v(5pt)
    text(size: 12pt, weight: 700, fill: accent, grid(
      columns: (1fr, auto),
      [Invoice total],
      money(totals.totalValue),
    ))
  }),
))

// Pushes declarations and legal details to the bottom of the last page.
#v(1fr)

// Platform-owned declarations, selected by rules and saved in the payload.
#block(breakable: false, {
  label("Declarations")
  v(3pt)
  set text(size: 8pt)
  for (index, item) in data.declarations.enumerate() {
    block(above: 3pt, below: 0pt, grid(
      columns: (12pt, 1fr),
      text(fill: muted, str(index + 1) + "."),
      item.text,
    ))
  }

  if optional(supplier, "terms") != none {
    v(8pt)
    label("Terms")
    v(2pt)
    supplier.terms
  }

  v(8pt)
  line(length: 100%, stroke: 0.5pt + hairline)
  v(4pt)
  set text(size: 7.5pt, fill: muted)
  [Registered office: #supplier.registeredOfficeAddress \ ]
  [CIN #supplier.cin]
  if optional(supplier, "pan") != none [ · PAN #supplier.pan]
  if optional(supplier, "contactEmail") != none [ · #supplier.contactEmail]
  if optional(supplier, "contactPhone") != none [ · #supplier.contactPhone]
})
