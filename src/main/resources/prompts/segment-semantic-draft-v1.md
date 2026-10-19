version: segment-semantic-draft-prompt/2

Return exactly one raw JSON object matching this closed SemanticDraft contract. Output JSON only: no
Markdown, prose, SQL, database identifiers, execution instructions or additional fields.

Root shape:
{"draftVersion":"1.0","facts":[fact,...],"globalSlots":[slot,...]}

Fact shape:
{"factId":"f1","sourceSpan":{"text":"exact non-empty substring","start":0,"end":1},
 "searchTerms":["business term",...],"slots":[slot,...]}

Slot shape:
{"slotType":"CAPABILITY_TYPE|CONCEPT|DEFINITION|AGGREGATION|OPERATOR|VALUE|TIME_WINDOW|
 SCOPE_FILTER|EVENT_SELECTOR|LOGICAL_GROUPING|NEGATION|CURRENCY|EXPRESSION",
 "status":"RESOLVED|AMBIGUOUS|PROPOSED|MISSING|UNSUPPORTED","value":string-or-null,
 "candidates":[{"value":"machine value","label":"user-facing label","modelSuggested":false},...],
 "reasonCode":string-or-null}

Every semantic value is represented by a slot and every slot explicitly states whether it is resolved,
ambiguous, missing or unsupported. Never hide uncertainty in a note or silently choose a preferred reading.

Slot invariants:
- RESOLVED has one non-empty value, an empty candidates array and null reasonCode.
- AMBIGUOUS has null value, at least two distinct candidates and a non-empty stable uppercase reasonCode.
- PROPOSED has null value, at least one model-proposed candidate and a non-empty stable uppercase reasonCode.
  A proposal is never resolved and never executable until the user accepts or replaces it and the host
  converts it into a typed validated contract.
- at most one candidate is modelSuggested. A PROPOSED slot requires exactly one modelSuggested candidate.
  This is an unverified model preference and never changes the slot to RESOLVED.
- MISSING and UNSUPPORTED have null value, an empty candidates array and a non-empty stable uppercase reasonCode.
- slotType is unique inside one fact and unique inside globalSlots.
- never repeat a slotType to list several Metadata definitions. A composite calculation has one EXPRESSION
  slot; its component Metrics are part of that expression proposal, not repeated DEFINITION slots.
- candidate values are machine values; labels are concise user-facing descriptions.

Stage behavior is supplied after this contract:
- DISCOVERY receives no Metadata. Split the utterance into complete business facts, preserve exact UTF-16
  source spans, identify capability types and semantic language, and emit searchTerms suitable for Metadata
  retrieval. A Metadata DEFINITION cannot be resolved during DISCOVERY; represent it as MISSING with
  reasonCode METADATA_NOT_LOADED.
- METADATA_REFINEMENT receives the frozen first draft and permitted structured Metadata. Preserve every
  factId and sourceSpan exactly. Resolve only from supplied stable IDs. If two or more supplied definitions
  remain valid, emit AMBIGUOUS rather than selecting one. If none is valid, emit MISSING. Add any newly
  discovered slot ambiguity, including value identifier kind, time-window scope, event selector and logical
  grouping.

When a requested Metric has no supplied definition, keep DEFINITION as MISSING with reasonCode
METRIC_DEFINITION_NOT_FOUND. Do not invent a Metric ID or mark the requirement unsupported merely because a
named Metric is absent: the host may ask the user to describe a calculation from governed atomic Metrics.
Physical tables, columns, SQL fragments and scripts are never valid Metric descriptions.
For METRIC_EXPRESSION, DEFINITION refers only to an exact named composite Metric. When no such named composite
definition exists, it remains MISSING even when all component atomic Metrics exist.
When one or more plausible calculations can be described using supplied governed Metrics, also add an
EXPRESSION slot with status PROPOSED, reasonCode MODEL_CALCULATION_PROPOSAL, and one to four candidates.
Candidate values are proposal_1, proposal_2 and so on; candidate labels explain the calculation in concise
business language. Mark the proposal the model considers most faithful modelSuggested=true and all others
false. This preference is unverified and every proposal remains non-executable until user confirmation and
host validation.

CAPABILITY_TYPE values are ATTRIBUTE, METRIC, RELATION, EVENT or METRIC_EXPRESSION. A Metric's row-level
product, category, channel, store or other restriction belongs to SCOPE_FILTER. A calculation involving two
or more Metrics uses METRIC_EXPRESSION. A selected first/latest event uses EVENT_SELECTOR.
The target subject words “顾客”, “会员”, “用户” and their equivalents identify MEMBER as the result subject;
they are not row-level SCOPE_FILTER values and must not create a SCOPE_FILTER slot by themselves.
Do not create a standalone fact for a result-subject phrase such as “顾客”, “会员” or “用户”. The subject is
implicit in every fact and those words only delimit the requested result population.
Emit SCOPE_FILTER only when the utterance actually restricts rows by a dimension or event subtype. Do not
emit a missing placeholder merely because Metadata is not loaded, and remove such a discovery placeholder
during refinement when no source text supports a row restriction.

Generic ratio semantics:
- language equivalent to “X占Y”, “X与Y的比例” or “X/Y” defines numerator X and denominator Y
- preserve the denominator exactly; never replace Y with a subset, complement or nearby Metric
- a word equivalent to “total” means the complete population explicitly named by Y
- when that total is represented by several disjoint supplied atomic Metrics, the denominator must combine
  all of those components; using only one component is not a valid proposal
- propose alternatives only when the denominator phrase itself has more than one reasonable definition
- a recommendation must be the candidate most faithful to the explicit numerator and denominator, not the
  most common business KPI
- never recommend or propose a formula that contradicts an explicit denominator merely because its name is
  a familiar KPI label

globalSlots carry ambiguity that applies across facts, especially AND/OR grouping and whether a time phrase
scopes one fact or several facts. Do not duplicate a fact-local ambiguity globally.

Mandatory coordinated-Metric window audit:
- inspect every coordinated sequence containing two or more METRIC or METRIC_EXPRESSION facts
- when an earlier fact has an explicit bounded time phrase and a later coordinated fact has no explicit
  bounded time phrase, determine whether the earlier phrase can grammatically scope the later fact
- language equivalent to “累计”, “总计” or “合计” does not prove LIFETIME when it may inherit the earlier bound
- in that case the later fact requires a TIME_WINDOW slot with status AMBIGUOUS, reasonCode
  AMBIGUOUS_WINDOW_SCOPE, and candidates INHERIT_PREVIOUS_WINDOW and LIFETIME
- never return every relevant fact resolved while this coordinated-window ambiguity remains

Before returning, verify that every meaningful source substring belongs to one fact or one global ambiguity,
all spans exactly match the indexed utterance, all facts from the first draft survive refinement, and no
Metadata stable ID was invented.
