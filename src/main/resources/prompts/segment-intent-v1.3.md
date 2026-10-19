version: segment-intent-prompt/22

Return exactly one raw JSON object matching the Segment Intent schema and version.
Output JSON only: no Markdown fence, no prose, no SQL, no trusted context, no execution instructions and no additional fields.
A user-supplied row constraint on a Metric aggregate must be nested in that clause's scopeFilters array; never flatten it into a sibling clause or encode it as relationScope.
For an ORDERED_ENUM comparison, preserve the user's comparison operator and return one ENUM boundary value; do not pre-expand the boundary into an IN list.
Every concept must be an uppercase machine code and every source span must be exact.

Return one formal Segment Intent v1.3 JSON object. The strict host parser rejects unknown fields,
missing fields, prose, Markdown, SQL and values that do not match these closed shapes.

Root fields:
- intentVersion: exactly "1.3"
- subject: exactly "MEMBER"
- clauses: non-empty array of unique clauses
- logicalExpression: references every clauseId exactly once
- ambiguities: array using only AMBIGUOUS_GROUPING, MISSING_CURRENCY,
  AMBIGUOUS_METRIC_MEANING, AMBIGUOUS_NULL_INCLUSION, AMBIGUOUS_RELATION_SCOPE,
  AMBIGUOUS_FILTER_SCOPE, AMBIGUOUS_WINDOW, AMBIGUOUS_DEFINITION, MISSING_METADATA_DEFINITION.
  Other entries are strings. A window or
  filter-scope ambiguity with known alternatives must use its structured shape instead of a string.
- AMBIGUOUS_WINDOW and AMBIGUOUS_FILTER_SCOPE are the only ambiguity codes that may use an object.
  Every other ambiguity, including MISSING_METADATA_DEFINITION, must be a bare JSON string such as
  "MISSING_METADATA_DEFINITION"; never attach target, detail or any other fields.
- unsupportedRequirements: array using only RAW_SQL, SCRIPT, ARBITRARY_JSON_QUERY, GEO,
  PREDICTIVE_SEGMENT, AI_CREATED_METADATA, CROSS_TENANT_LOOKUP, AUTOMATIC_PUBLISH,
  PRODUCTION_MUTATION, PERMISSION_BYPASS, DATASCOPE_BYPASS, TENANT_OVERRIDE,
  INSTRUCTION_INJECTION

Clause required fields:
- clauseId, concept, definitionKind, negated, provenance, candidateHints, assumptions
Clause optional fields:
- operator, value, window, currency, aggregation, relationScope, scopeFilters
- omit every optional field that is not semantically applicable; do not emit it as null
- ATTRIBUTE clauses must not carry window, currency, aggregation, relationScope or scopeFilters
- METRIC clauses carry only windows and aggregations supported by their projected definition
- words meaning different, distinct, unique or de-duplicated require a projected COUNT_DISTINCT Metric
  over the named business dimension; never substitute SUM(quantity), COUNT(rows) or COUNT(orders)
- concept must be one projected conceptCodes value when available
- definitionKind is ATTRIBUTE, METRIC or RELATION
- operator must be one allowedOperators value projected by the selected definition; never apply
  ordering operators to ENUM. For ORDERED_ENUM, preserve the user's ordering operator and emit one
  ENUM boundary value; the deterministic host Resolver expands the boundary by Metadata rank.
- candidateHints contain only projected stableId values
- provenance for user text is exactly
  {"type":"SOURCE_SPAN","text":"exact substring","start":0,"end":1}
  with zero-based UTF-16, end-exclusive indexes into the complete utterance
- use the supplied UTF-16 index guide; verify utterance.substring(start,end) equals text exactly
- compute end as start plus the exact Java UTF-16 length of text; spaces and punctuation before or
  inside the source text count toward indexes and must never be skipped
- assumptions contain uppercase machine codes only; never use assumptions to hide ambiguity

Scalar value: {"kind":"STRING|BOOLEAN|INTEGER|DECIMAL|DATE|TIMESTAMP|ENUM|MONEY","raw":...}
The value kind must equal the selected projected dataType: STRING->STRING, BOOLEAN->BOOLEAN,
INTEGER->INTEGER, DECIMAL->DECIMAL, DATE->DATE, TIMESTAMP->TIMESTAMP, ENUM->ENUM,
ORDERED_ENUM->ENUM and MONEY->MONEY. For ENUM and ORDERED_ENUM, preserve the exact user label in
raw and let the host resolve it; never replace a user label with a Metadata enum code. For example,
a user label must remain an ENUM raw label.
BOOLEAN raw must be the JSON boolean true or false, never the strings "true" or "false".
INTEGER, DECIMAL and MONEY raw values are canonical number strings only. Do not include a unit,
currency symbol or words in raw; for example use "1000", never "1000元" or "CNY 1000".
List value: {"kind":"LIST","items":[scalar,...]}
Range value: {"kind":"RANGE","lower":scalar,"upper":scalar,
  "includeLower":true,"includeUpper":false}
Null operators carry no value. IN and NOT_IN require LIST. BETWEEN requires RANGE.
Every DATE or TIMESTAMP ATTRIBUTE range must be lower-inclusive and upper-exclusive:
includeLower=true and includeUpper=false. For the last N days or weeks, use BETWEEN from
asOf-minus-N-units through the exclusive asOf upper bound. For the next N days or weeks, use
BETWEEN from the inclusive asOf lower bound through the exclusive asOf-plus-N-units upper bound.
Never lower a bounded relative-time phrase to only GREATER_OR_EQUAL or only LESS_OR_EQUAL:
omitting either bound changes the requested interval. When the user supplies an inclusive calendar
end date, convert it to the next exact calendar boundary and keep includeUpper=false.
For a TIMESTAMP ATTRIBUTE, both bounds are exact instants: the upper bound must represent the
exact supplied asOf instant, and the lower bound is that instant minus N*24 hours or N*7*24 hours.
Preserve the time of day. Do not round either bound to local midnight. If changing the offset,
convert the clock time so the instant stays identical; never copy the same clock time under a
different offset. For example, 12:00Z is 20:00+08:00, not 12:00+08:00.

Window:
- {"kind":"ROLLING","amount":positive-integer,"unit":"DAY|WEEK"}
- {"kind":"LIFETIME"}
- {"kind":"FIXED","start":"ISO-8601 instant","end":"ISO-8601 instant"}
- {"kind":"CALENDAR_MONTHS","count":positive-integer-up-to-24,"includeCurrent":boolean,
  "bucketRequirement":"COMBINED|EVERY_BUCKET"}
- {"kind":"RELATIVE_TO_ATTRIBUTE","anchorDefinitionId":"projected.temporal.attribute.id",
  "startOffset":signed-integer,"endOffset":signed-integer,"unit":"DAY|WEEK"}
- {"kind":"SELECTED_EVENT","selectorId":"projected.event.selector.id",
  "selectorScopeFilters":[scope-filter,...]}
- {"kind":"RELATIVE_TO_EVENT","selectorId":"projected.event.selector.id",
  "selectorScopeFilters":[scope-filter,...],"startOffset":signed-integer,
  "endOffset":signed-integer,"unit":"DAY|WEEK","excludeAnchorEvent":boolean}
- every FIXED window is [start,end): start is inclusive and end is exclusive
- every RELATIVE_TO_ATTRIBUTE window is
  [anchor Attribute + startOffset, anchor Attribute + endOffset); startOffset must be less than
  endOffset, and the anchor must be a projected DATE or TIMESTAMP ATTRIBUTE on the Metric subject
- use RELATIVE_TO_ATTRIBUTE only when the user explicitly anchors an aggregate period to a
  per-subject Attribute, such as a period before or after that Attribute; never replace that
  meaning with a ROLLING or FIXED window relative to asOf
- use SELECTED_EVENT when an aggregate applies to the governed event itself, such as the contents
  of a first order. Use RELATIVE_TO_EVENT for activity before or after that event. selectorId must
  be a projected eventSelectors stableId with a binding whose relationId equals the chosen Metric's
  projected metricRelationId. Never approximate
  event identity with a zero-length time window or only an Attribute timestamp.
- selectorScopeFilters define the event stream before ordinal selection. Use them only for an
  explicit scope such as a named channel. If the selector is not unscopedByDefault and the user
  omitted its required scope, record AMBIGUOUS_RELATION_SCOPE. Never invent a channel.
- for “after the selected event”, set excludeAnchorEvent=true so the anchor order is not counted
  even when another order shares its timestamp. Event identity and deterministic tie-breaking are
  host-owned Metadata semantics; never synthesize them.
- if an anchor represents the first occurrence of the same counted event, offset zero includes
  that first occurrence. Therefore “no second occurrence in the interval” is COUNT <= 1, not
  COUNT = 0; apply the same ordinal-count rule generically
- when the user supplies inclusive calendar dates without times, use local midnight at the first
  date as start and local midnight on the calendar day after the final date as end, using the
  supplied temporal-context timezone; never use 23:59:59 or subtract a second from the end
- when the user supplies an explicit time, preserve that exact boundary and offset
MONTH and YEAR are not legal rolling units. Never convert a calendar month/year phrase into a
day count. Record AMBIGUOUS_WINDOW when the utterance does not determine a legal exact window.

Structured window ambiguity:
- when one time phrase can reasonably scope only one coordinated Metric clause or also later
  coordinated Metric clauses, never silently choose one reading
- keep one legal candidate window on the target clause so the clause remains structurally valid,
  but emit this object in ambiguities so the host blocks compilation and asks the user
- shape: {"code":"AMBIGUOUS_WINDOW","target":"targetClauseId.window","options":[...]}
- reuse an earlier clause's exact window with
  {"kind":"INHERIT_WINDOW","fromClauseId":"sourceClauseId"}
- an alternative explicit window uses any normal Window shape, such as {"kind":"LIFETIME"}
- options contain two to eight distinct legal alternatives and target exactly one declared clause
- example: if a leading "last 90 days" may apply to c1 only or to both c1 and c2, and c2 also says
  "cumulative", emit {"code":"AMBIGUOUS_WINDOW","target":"c2.window","options":[
  {"kind":"INHERIT_WINDOW","fromClauseId":"c1"},{"kind":"LIFETIME"}]}

Calendar-month rules:
- one natural/calendar month is [local midnight on its first day, local midnight on the first day
  of the next month) in the supplied temporal-context timezone; month lengths and DST come from
  that timezone's calendar and must never be converted to 30 days
- unqualified "last/近 N natural/calendar months" means the N complete calendar months immediately
  before the local calendar month containing asOf; it excludes the incomplete current month
- an explicit "including the current month/含本月" includes the current partial bucket from local
  month start through the exact asOf instant, plus the preceding buckets needed to total N
- never calculate calendar-month boundaries and never emit monthly FIXED clauses yourself; emit one
  CALENDAR_MONTHS window and let the trusted host compute exact boundaries from asOf and timezone
- use bucketRequirement COMBINED when the Metric applies once over the whole N-month range
- use bucketRequirement EVERY_BUCKET for "in every month/每个月" so the trusted host applies the
  same Metric condition independently to every month and joins all buckets with ALL
- count must be at most 24; above that, record AMBIGUOUS_WINDOW instead of truncating or approximating

Money uses value kind MONEY. Add currency {"mode":"EXPLICIT","code":"ISO-4217"} only when the
utterance explicitly grounds that currency. When Registered model defaults projects a reportingCurrency,
omit currency and do not record MISSING_CURRENCY; the deterministic host applies that registered default.
If no currency is grounded and no registered default is projected, record MISSING_CURRENCY.

A Metric row constraint must be nested in scopeFilters, never emitted as a sibling clause:
{"filterId":"...","concept":"...","domain":"PRODUCT|CATEGORY|UNSPECIFIED",
 "identifierKind":"NAME|CODE|UNSPECIFIED","operator":"...","value":scalar-or-list-or-range,
 "provenance":source-span,"candidateHints":["projected.attribute.id"],"assumptions":[]}
When a scope-filter value is MONEY, use the projected monetary Attribute and put any explicit or registered
currency on its parent Metric clause; never replace a unit-price comparison with a product identity field.
Use only filter dimensions projected on that Metric.
Set domain and identifierKind only from explicit user semantics. A domain-only expression uses
the explicit domain with identifierKind UNSPECIFIED. A bare value uses UNSPECIFIED for both.
Never infer either field from how a value looks.
If the utterance names a business property, classification, type or state but no projected Attribute
explicitly represents that property, never reinterpret its label as a product name, SKU, category, store,
employee or another unrelated field. Keep the requested concept as a new uppercase semantic code, leave
candidateHints empty, and add MISSING_METADATA_DEFINITION. The host must ask which source field or reusable
calculation defines it; do not create Metadata and do not offer structurally compatible unrelated fields.

relationScope: an object on a RELATION clause that declares how the two linked subjects correlate.
- {"joinKind":"GLOBAL"} — every member of the source subject matches every member of the target subject;
  sourceJoinField and targetJoinField must both be absent.
- {"joinKind":"CORRELATED_BY_SOURCE_KEY","sourceJoinField":"projected.source.field.id",
  "targetJoinField":"projected.target.field.id"} — members correlate when the named source field
  value equals the named target field value. Both fields must be projected id-scope identifiers.
- {"joinKind":"TARGET_FILTERED","targetJoinField":"projected.target.field.id"} — every source member
  matches exactly one target member identified by the named target field. sourceJoinField must be absent.
- Omit relationScope entirely when relationScope is not semantically applicable (i.e. ATTRIBUTE or METRIC
  clauses, or RELATION clauses where the scope is unambiguously governed by host Metadata).

When a row constraint can reasonably qualify only one purchase or existence fact, or can also constrain a
coordinated aggregate that follows it, never silently choose either scope. Keep one structurally legal
interpretation and emit {"code":"AMBIGUOUS_FILTER_SCOPE","target":"targetClauseId.scopeFilters",
"options":[{"kind":"INHERIT_SCOPE_FILTERS","fromClauseId":"sourceClauseId"},{"kind":"INDEPENDENT"}]}.
The target is the coordinated Metric whose aggregate scope is uncertain. For example, in "bought items with
unit price at least 1000 and cumulative amount at least 3000", the amount may mean only those qualifying
items or all purchases; an empty ambiguities array is invalid.

logicalExpression shapes:
- {"type":"CLAUSE","clauseId":"..."}
- {"type":"ALL|ANY","children":[expression,expression,...]}
- {"type":"NOT","child":expression}

Encode a negative occurrence exactly once. For "never/no event", use either a non-negated COUNT
EQUAL 0 clause or a negated COUNT GREATER_THAN 0 clause. Never combine negated=true with EQUAL 0,
because that is a double negative and means the event did occur.

Do not decide whether the Intent is resolved. The deterministic host resolver computes the
disposition from this formal document, the frozen Metadata snapshot and the frozen policy.

Mandatory coordinated-window ambiguity audit immediately before output:
- inspect every ALL or ANY group containing two or more Metric clauses
- if a bounded time phrase attached before an earlier Metric can grammatically scope either only
  that Metric or later coordinated Metrics, and a later Metric has a different candidate window,
  a structured AMBIGUOUS_WINDOW entry for each affected later clause is mandatory
- words meaning cumulative, total or aggregate do not by themselves prove LIFETIME when an earlier
  bounded time phrase may scope the coordinated clause; preserve both interpretations
- under those conditions an empty ambiguities array is invalid, even when one reading seems more
  likely. Do not choose a preferred reading and do not hide the uncertainty in assumptions.

Mandatory coordinated-filter-scope ambiguity audit immediately before output:
- inspect every ALL or ANY group containing a row-constrained Metric and another coordinated Metric
- if the row constraint can grammatically apply either only to its local fact or also to the other Metric,
  AMBIGUOUS_FILTER_SCOPE is mandatory
- cumulative, total and aggregate wording does not prove that the later Metric is independent of the row
  constraint; do not copy or drop the filter by guesswork
- under those conditions an empty ambiguities array is invalid and SQL compilation must remain blocked.

Before returning, perform these generic checks: output is one raw JSON object; optional absent
fields are omitted; every numeric raw is canonical; every source span exactly matches the indexed
utterance; every value kind matches projected dataType; every candidate hint and concept appears
in the permitted Metadata projection; every distinct-dimension request uses COUNT_DISTINCT.
