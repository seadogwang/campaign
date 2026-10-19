#!/usr/bin/env python3
"""Tests for invoke_segment_model: metadata sanitization, window validation, and drift rejection."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

# Ensure the scripts directory is importable
_script_dir = Path(__file__).resolve().parent
if str(_script_dir) not in sys.path:
    sys.path.insert(0, str(_script_dir))

from invoke_segment_model import (
    ALLOWED_WINDOW_KINDS,
    ALLOWED_WINDOW_UNITS,
    ALLOWED_BUCKET_REQUIREMENTS,
    WINDOW_FIELDS_BY_KIND,
    WINDOW_MAX_ROLLING_AMOUNT,
    WINDOW_MAX_CALENDAR_MONTHS,
    allowed_semantic_ids,
    parse_model_json,
    public_metadata,
    validate_window,
)


# ---------------------------------------------------------------------------
# 1. Metadata sanitisation
# ---------------------------------------------------------------------------

class MetadataSanitisationTests(unittest.TestCase):
    """public_metadata must work with v1.3 and never leak host-only content."""

    def test_v13_public_metadata_strips_host_only_content(self):
        """v1.3 catalog must strip Storage catalog, resource paths, and
        Section 2 (raw table coverage) while keeping semantic content."""
        path = _script_dir.parent / "docs" / "campaign_ai_metadata_catalog_v1.3.md"
        self.assertTrue(path.is_file(), f"v1.3 catalog not found at {path}")
        result = public_metadata(path)
        self.assertIsInstance(result, str)
        self.assertGreater(len(result), 500,
                           "Public metadata should contain substantial content")
        # Host-only content must be absent
        self.assertNotIn("Storage catalog", result)
        self.assertNotIn("src/main/resources/metadata/", result)
        self.assertNotIn("vw_crm_", result)
        self.assertNotIn("原始表覆盖", result)
        # Public semantic content must be preserved
        self.assertIn("member.current_tier", result)
        self.assertIn("order.positive_count", result)
        self.assertIn("member.orders", result)

    def test_v13_does_not_leak_physical_column_names(self):
        """Storage JSON column names like ``valueColumn``, physical table
        names like ``seg_consent_event``, and ``vw_crm_*`` must not appear."""
        path = _script_dir.parent / "docs" / "campaign_ai_metadata_catalog_v1.3.md"
        result = public_metadata(path)
        forbidden = ["valueColumn", "primaryKeyColumn", "currencyColumn",
                     "tenantColumn", "programColumn",
                     "seg_consent_event", "seg_redemption_item",
                     "vw_crm_"]
        for token in forbidden:
            self.assertNotIn(
                token, result,
                f"Host-only physical token {token!r} leaked into model prompt"
            )

    def test_sentinel_stripping(self):
        """Content between BEGIN_HOST_ONLY / END_HOST_ONLY markers is removed."""
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".md", encoding="utf-8", delete=False
        ) as f:
            f.write(
                "## 1. Public\n"
                "semantic attribute: member.tier\n\n"
                "<!-- BEGIN_HOST_ONLY -->\n"
                "## Host-Only Serving Views\n"
                "Physical table: seg_member\n"
                "column: tier_code -> valueColumn\n"
                "<!-- END_HOST_ONLY -->\n\n"
                "## 2. More Public\n"
                "reliability rules\n"
            )
            tmp_path = Path(f.name)

        try:
            result = public_metadata(tmp_path)
            self.assertIn("## 1. Public", result)
            self.assertIn("## 2. More Public", result)
            self.assertNotIn("Host-Only Serving Views", result)
            self.assertNotIn("seg_member", result)
            self.assertNotIn("BEGIN_HOST_ONLY", result)
            self.assertNotIn("END_HOST_ONLY", result)
        finally:
            tmp_path.unlink(missing_ok=True)

    def test_heading_keyword_fallback(self):
        """If no sentinels, a heading containing 'serving view' is stripped."""
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".md", encoding="utf-8", delete=False
        ) as f:
            f.write(
                "## 1. Public Attributes\n"
                "member.tier\n\n"
                "## 6. Serving Views (host-only)\n"
                "storage mapping details\n"
                "table: secret\n\n"
                "## 7. Reliability\n"
                "public rules\n"
            )
            tmp_path = Path(f.name)

        try:
            result = public_metadata(tmp_path)
            self.assertIn("Public Attributes", result)
            self.assertIn("Reliability", result)
            self.assertNotIn("Serving Views", result)
            self.assertNotIn("secret", result)
        finally:
            tmp_path.unlink(missing_ok=True)

    def test_no_host_only_section_passes_through(self):
        """A catalog with no host-only markers or headings is returned intact."""
        content = "## Public Only\nall content is public\n"
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".md", encoding="utf-8", delete=False
        ) as f:
            f.write(content)
            tmp_path = Path(f.name)

        try:
            result = public_metadata(tmp_path)
            self.assertEqual(result.strip(), content.strip())
        finally:
            tmp_path.unlink(missing_ok=True)


# ---------------------------------------------------------------------------
# 2. Canonical window schema constants
# ---------------------------------------------------------------------------

class WindowSchemaConstantsTests(unittest.TestCase):
    """Verify the canonical schema constants match the Java contract."""

    def test_allowed_kinds_match_metadata_enum(self):
        """The 6+1 window kinds match IntentWindow sealed hierarchy."""
        expected = {
            "ROLLING", "LIFETIME", "FIXED",
            "CALENDAR_MONTHS",
            "RELATIVE_TO_ATTRIBUTE",
            "SELECTED_EVENT", "RELATIVE_TO_EVENT",
        }
        self.assertEqual(ALLOWED_WINDOW_KINDS, expected)

    def test_allowed_units_day_week_only(self):
        """DAY and WEEK are the only legal rolling units."""
        self.assertEqual(ALLOWED_WINDOW_UNITS, {"DAY", "WEEK"})

    def test_bucket_requirements(self):
        self.assertEqual(ALLOWED_BUCKET_REQUIREMENTS, {"COMBINED", "EVERY_BUCKET"})

    def test_every_kind_has_closed_field_set(self):
        """Every allowed kind must have a declared field set."""
        for kind in ALLOWED_WINDOW_KINDS:
            self.assertIn(kind, WINDOW_FIELDS_BY_KIND,
                          f"Missing field set for window kind {kind!r}")

    def test_rolling_fields_require_kind_amount_unit(self):
        self.assertEqual(
            WINDOW_FIELDS_BY_KIND["ROLLING"],
            frozenset({"kind", "amount", "unit"}),
        )

    def test_lifetime_no_extra_fields(self):
        self.assertEqual(
            WINDOW_FIELDS_BY_KIND["LIFETIME"],
            frozenset({"kind"}),
        )


# ---------------------------------------------------------------------------
# 3. Window validation — valid structures
# ---------------------------------------------------------------------------

class WindowValidationValidTests(unittest.TestCase):
    """Canonical window shapes must pass validation."""

    def test_null_passes(self):
        validate_window(None)  # must not raise

    def test_lifetime_passes(self):
        validate_window({"kind": "LIFETIME"})

    def test_rolling_day_passes(self):
        validate_window({"kind": "ROLLING", "amount": 90, "unit": "DAY"})

    def test_rolling_week_passes(self):
        validate_window({"kind": "ROLLING", "amount": 1, "unit": "WEEK"})

    def test_rolling_max_amount_passes(self):
        validate_window({
            "kind": "ROLLING", "amount": WINDOW_MAX_ROLLING_AMOUNT, "unit": "DAY"
        })

    def test_fixed_passes(self):
        validate_window({
            "kind": "FIXED",
            "start": "2025-01-01T00:00:00Z",
            "end": "2025-03-01T00:00:00Z",
        })

    def test_calendar_months_combined_passes(self):
        validate_window({
            "kind": "CALENDAR_MONTHS",
            "count": 6,
            "includeCurrent": False,
            "bucketRequirement": "COMBINED",
        })

    def test_calendar_months_every_bucket_passes(self):
        validate_window({
            "kind": "CALENDAR_MONTHS",
            "count": 3,
            "includeCurrent": True,
            "bucketRequirement": "EVERY_BUCKET",
        })

    def test_relative_to_attribute_passes(self):
        validate_window({
            "kind": "RELATIVE_TO_ATTRIBUTE",
            "anchorDefinitionId": "member.registered_at",
            "startOffset": -90,
            "endOffset": 0,
            "unit": "DAY",
        })

    def test_selected_event_passes(self):
        validate_window({
            "kind": "SELECTED_EVENT",
            "selectorId": "order.first_positive",
            "selectorScopeFilters": [],
        })

    def test_relative_to_event_passes(self):
        validate_window({
            "kind": "RELATIVE_TO_EVENT",
            "selectorId": "order.first_positive",
            "selectorScopeFilters": [],
            "startOffset": -90,
            "endOffset": 0,
            "unit": "DAY",
            "excludeAnchorEvent": True,
        })


# ---------------------------------------------------------------------------
# 4. Window validation — drift structures rejected
# ---------------------------------------------------------------------------

class WindowValidationRejectionTests(unittest.TestCase):
    """Drift structures that do not match the canonical contract are rejected."""

    # -- Wrong discriminator -------------------------------------------------
    def test_type_discriminator_rejected(self):
        """{type:ROLLING,...} uses 'type' instead of 'kind'."""
        with self.assertRaises(ValueError) as ctx:
            validate_window({"type": "ROLLING", "amount": 90, "unit": "DAY"})
        self.assertIn("kind", str(ctx.exception))

    # -- Drift field names ---------------------------------------------------
    def test_days_field_rejected(self):
        """{kind:ROLLING,days:90} is a drift — canonical is amount+unit."""
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "ROLLING", "days": 90})
        self.assertIn("unexpected", str(ctx.exception))
        self.assertIn("days", str(ctx.exception))

    def test_relation_field_on_lifetime_rejected(self):
        """LIFETIME window must only contain 'kind'."""
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "LIFETIME", "relation": "member.orders"})
        self.assertIn("unexpected", str(ctx.exception))
        self.assertIn("relation", str(ctx.exception))

    def test_reference_field_on_rolling_rejected(self):
        """ROLLING window rejects arbitrary 'reference' field."""
        with self.assertRaises(ValueError) as ctx:
            validate_window({
                "kind": "ROLLING", "amount": 30, "unit": "DAY",
                "reference": "some.id",
            })
        self.assertIn("unexpected", str(ctx.exception))
        self.assertIn("reference", str(ctx.exception))

    def test_end_field_on_rolling_rejected(self):
        """ROLLING window rejects arbitrary 'end' field (only FIXED has end)."""
        with self.assertRaises(ValueError) as ctx:
            validate_window({
                "kind": "ROLLING", "amount": 30, "unit": "DAY",
                "end": "2025-06-01T00:00:00Z",
            })
        self.assertIn("unexpected", str(ctx.exception))
        self.assertIn("end", str(ctx.exception))

    # -- Invalid unit --------------------------------------------------------
    def test_month_unit_rejected(self):
        """MONTH is explicitly illegal as a rolling unit."""
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "ROLLING", "amount": 3, "unit": "MONTH"})
        self.assertIn("unit", str(ctx.exception))
        self.assertIn("MONTH", str(ctx.exception))

    def test_year_unit_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "ROLLING", "amount": 1, "unit": "YEAR"})
        self.assertIn("unit", str(ctx.exception))
        self.assertIn("YEAR", str(ctx.exception))

    def test_days_unit_rejected(self):
        """Only singular DAY, not plural DAYS."""
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "ROLLING", "amount": 90, "unit": "DAYS"})
        self.assertIn("unit", str(ctx.exception))

    # -- Invalid kind --------------------------------------------------------
    def test_unknown_kind_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "SLIDING"})
        self.assertIn("kind", str(ctx.exception))
        self.assertIn("SLIDING", str(ctx.exception))

    # -- Missing required fields ---------------------------------------------
    def test_rolling_missing_unit_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "ROLLING", "amount": 90})
        self.assertIn("unit", str(ctx.exception))

    def test_rolling_missing_amount_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "ROLLING", "unit": "DAY"})
        self.assertIn("amount", str(ctx.exception))

    def test_fixed_missing_end_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "FIXED", "start": "2025-01-01T00:00:00Z"})
        self.assertIn("end", str(ctx.exception))

    # -- Type violations -----------------------------------------------------
    def test_amount_not_integer_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "ROLLING", "amount": "90", "unit": "DAY"})
        self.assertIn("integer", str(ctx.exception))

    def test_amount_boolean_rejected(self):
        """Booleans are a subclass of int in Python — must be rejected."""
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "ROLLING", "amount": True, "unit": "DAY"})
        self.assertIn("integer", str(ctx.exception))

    def test_include_current_not_boolean_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_window({
                "kind": "CALENDAR_MONTHS",
                "count": 3,
                "includeCurrent": "yes",
                "bucketRequirement": "COMBINED",
            })
        self.assertIn("boolean", str(ctx.exception))

    # -- Bounds violations ---------------------------------------------------
    def test_rolling_amount_zero_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "ROLLING", "amount": 0, "unit": "DAY"})
        self.assertIn("positive", str(ctx.exception))

    def test_rolling_amount_negative_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_window({"kind": "ROLLING", "amount": -5, "unit": "DAY"})
        self.assertIn("positive", str(ctx.exception))

    def test_calendar_months_count_zero_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_window({
                "kind": "CALENDAR_MONTHS",
                "count": 0,
                "includeCurrent": False,
                "bucketRequirement": "COMBINED",
            })
        self.assertIn("positive", str(ctx.exception))

    def test_calendar_months_exceeds_max_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_window({
                "kind": "CALENDAR_MONTHS",
                "count": WINDOW_MAX_CALENDAR_MONTHS + 1,
                "includeCurrent": False,
                "bucketRequirement": "COMBINED",
            })
        self.assertIn("bounds", str(ctx.exception))

    # -- Non-dict window -----------------------------------------------------
    def test_string_window_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_window("ROLLING")
        self.assertIn("object", str(ctx.exception))


# ---------------------------------------------------------------------------
# 5. parse_model_json integration
# ---------------------------------------------------------------------------

class ParseModelJsonWindowIntegrationTests(unittest.TestCase):
    """parse_model_json must validate windows inside resolvedFacts."""

    _VALID_SEMANTIC_IDS = {"member.tier", "member.registered_at", "member.status",
                           "order.positive_count", "order.channel"}

    def _valid_output(self, **overrides) -> dict:
        base = {
            "disposition": "RESOLVED",
            "clarificationQuestions": [],
            "resolvedFacts": [],
            "unresolvedFacts": [],
        }
        base.update(overrides)
        return base

    def test_valid_rolling_window_accepted(self):
        output = self._valid_output(resolvedFacts=[{
            "sourceText": "近90天",
            "semanticId": "order.positive_count",
            "operator": "GREATER_THAN",
            "value": None,
            "window": {"kind": "ROLLING", "amount": 90, "unit": "DAY"},
        }])
        # must not raise
        result = parse_model_json(json.dumps(output), self._VALID_SEMANTIC_IDS)
        self.assertEqual(result["disposition"], "RESOLVED")

    def test_drift_window_rejected_in_parse(self):
        output = self._valid_output(resolvedFacts=[{
            "sourceText": "近90天",
            "semanticId": "order.positive_count",
            "operator": "GREATER_THAN",
            "value": None,
            "window": {"type": "ROLLING", "days": 90},
        }])
        with self.assertRaises(ValueError) as ctx:
            parse_model_json(json.dumps(output), self._VALID_SEMANTIC_IDS)
        self.assertIn("kind", str(ctx.exception))

    def test_null_window_accepted(self):
        output = self._valid_output(resolvedFacts=[{
            "sourceText": "会员等级为金卡",
            "semanticId": "member.tier",
            "operator": "EQUAL",
            "value": None,
            "window": None,
        }])
        parse_model_json(json.dumps(output), self._VALID_SEMANTIC_IDS)

    def test_valid_lifetime_window_accepted(self):
        output = self._valid_output(resolvedFacts=[{
            "sourceText": "历史累计",
            "semanticId": "order.positive_count",
            "operator": "GREATER_THAN",
            "value": None,
            "window": {"kind": "LIFETIME"},
        }])
        parse_model_json(json.dumps(output), self._VALID_SEMANTIC_IDS)

    def test_extra_field_on_lifetime_rejected(self):
        output = self._valid_output(resolvedFacts=[{
            "sourceText": "历史累计",
            "semanticId": "order.positive_count",
            "operator": "GREATER_THAN",
            "value": None,
            "window": {"kind": "LIFETIME", "end": "2025-01-01"},
        }])
        with self.assertRaises(ValueError) as ctx:
            parse_model_json(json.dumps(output), self._VALID_SEMANTIC_IDS)
        self.assertIn("unexpected", str(ctx.exception))


# ---------------------------------------------------------------------------
# 6. Semantic ID extraction
# ---------------------------------------------------------------------------

class SemanticIdExtractionTests(unittest.TestCase):

    def test_extracts_ids_from_metric_table(self):
        text = (
            "| ID | Aggregation |\n"
            "|---|---|\n"
            "| `order.positive_count` | COUNT |\n"
            "| `order.gross_amount_sum` | SUM MONEY |\n"
        )
        ids = allowed_semantic_ids(text)
        self.assertIn("order.positive_count", ids)
        self.assertIn("order.gross_amount_sum", ids)

    def test_extracts_ids_from_live_catalog(self):
        path = _script_dir.parent / "docs" / "campaign_ai_metadata_catalog_v1.3.md"
        metadata = public_metadata(path)
        ids = allowed_semantic_ids(metadata)
        # Must include attributes, metrics, relations, event selectors
        self.assertIn("member.current_tier", ids)
        self.assertIn("order.positive_count", ids)
        self.assertIn("member.orders", ids)  # relation
        self.assertIn("order.first_positive", ids)  # event selector
        # Must NOT include physical table names
        for sid in ids:
            self.assertFalse(
                sid.startswith("vw_crm_"),
                f"Physical table name {sid!r} leaked into semantic IDs",
            )
        self.assertGreater(len(ids), 80,
                           "Expected 94+ attributes, 25 metrics, 8 relations, 1 event selector")


if __name__ == "__main__":
    unittest.main(verbosity=2)