# Needs Telemetry Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add flexible portal context breakdowns for modder-defined telemetry event `details`, then emit Tamework needs seek/consume diagnostics that group cleanly into issues and expose reason/resource/role context.

**Architecture:** Keep using the existing event `details` payload field, but present it in the portal as Event Context. Aggregate useful scalar context fields into generic key/value rollups rather than adding one table column per field. Portal issue pages continue showing universal Issue Signals separately, then show Context Breakdowns from promoted `details` fields. Tamework emits stable-fingerprint needs events through the existing embedded Alec's Telemetry runtime with local rate limits and no hard dependency on portal availability.

**Tech Stack:** Java/Maven for `AlecsTelemetry` and `alecstamework`; TypeScript/Postgres/React/Vitest for `AlecsTelemetryPlatform`.

---

## Integration Contract

- Primary Mod: Alec's Tamework
- External Tool: Alec's Telemetry runtime and Alec's Telemetry Platform
- Dependency: runtime is embedded through `alecstelemetry-runtime`; portal is optional hosted destination
- Version Range: Tamework uses Alec's Telemetry runtime `0.1.3` for descriptor-sanitized error details; platform changes accept existing schema-version 2 event payloads
- Exposed Hooks: Tamework emits `needs_seek_failed` and `needs_consume_failed`
- Failure Behavior: if telemetry is disabled, unavailable, or the hosted portal rejects an event, gameplay continues and local debug logging remains available
- Validation Cases: runtime enabled/disabled, portal facet ingestion on/off, Tamework debug telemetry on/off, and repeated failure throttling

## Compatibility Matrix

| Primary Version | External Version | Status | Notes |
| --- | --- | --- | --- |
| Tamework next release | Alec's Telemetry runtime 0.1.3 | pass expected | Existing `TelemetryEventContext` supports details, fingerprint, subsystem, feature, entity, and world context; `0.1.3` preserves descriptor-approved error details on issue-producing events. |
| Tamework next release | Alec's Telemetry Platform current branch | partial before portal work | Issues can group by stable fingerprint, but arbitrary Event Context breakdowns are not first-class yet. |
| Tamework next release | Hosted portal unavailable | pass expected | Events remain best-effort through existing telemetry runtime behavior; Tamework must not fail gameplay. |

## File Structure

### Alec's Telemetry Platform

- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\migrations\117_add_issue_context_rollups.sql`
  - Adds generic aggregate tables for `details` field discovery and issue context rollups.
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\src\db\repositories\telemetry-occurrence-repo.ts`
  - Updates context/discovery aggregates when accepted occurrences are recorded.
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\src\telemetry\event-context-fields.ts`
  - Extracts safe scalar values from event `rawJson.details` and normalizes field/value labels for portal context.
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\src\db\repositories\telemetry-issue-context-repo.ts`
  - Reads issue context breakdowns and per-value trends/releases.
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\src\portal\types.ts`
  - Adds the context repository dependency.
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\src\index.ts`
  - Instantiates the context repository.
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\shared\portal\contracts.ts`
  - Adds `PortalIssueContextBreakdown`, `PortalIssueContextValue`, and the new response field.
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\src\portal\routes\issue-routes.ts`
  - Includes context breakdowns in issue workspace responses.
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\portal-ui\src\features\issues\issue-detail-pages.tsx`
  - Renders Context Breakdowns separately from Issue Signals.
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\portal-ui\src\styles\features\issues-workspace.css`
  - Styles breakdown rows and compact trend/release metadata.
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\tests\telemetry-occurrence-repo.test.ts`
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\tests\telemetry-issue-context-repo.test.ts`
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\tests\portal-server-routes.test.ts`
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\portal-ui\src\features\issues\issue-detail-pages.test.tsx`

### Alec's Telemetry Runtime

- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\runtime\src\main\java\com\alechilles\alecstelemetry\project\TelemetryProjectDescriptor.java`
  - Adds descriptor-sanitized custom detail fields for error events.
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\runtime\src\main\java\com\alechilles\alecstelemetry\core\TelemetryCoreEngine.java`
  - Stores descriptor-sanitized details on error telemetry events.
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\runtime\src\main\java\com\alechilles\alecstelemetry\api\TelemetryEventContext.java`
  - Keep current arbitrary bounded scalar details; no API expansion is required for the first portal pass.
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\docs\project-descriptor.md`
  - Document that `details` fields are presented as Event Context and can become portal Context Breakdowns when they are low-cardinality scalar values.
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\docs\hosted-ingest-contract.md`
  - Document the portal's aggregate facet behavior and high-cardinality safeguards.
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\runtime\src\test\java\com\alechilles\alecstelemetry\project\TelemetryProjectDescriptorTest.java`
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\standalone\src\test\java\com\alechilles\alecstelemetry\runtime\TelemetryRuntimeServiceTest.java`

### Alec's Tamework

- Create: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsTelemetryDiagnostics.java`
  - Builds stable-fingerprint telemetry events and applies per-reason throttling.
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsSeekDiagnostics.java`
  - Calls telemetry diagnostics for failed seek results.
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsConsumeDiagnostics.java`
  - Calls telemetry diagnostics for failed consume results.
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsConsumeService.java`
  - Passes enough context to classify consume failures without duplicating service logic.
- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwDebugConfig.java`
  - Adds a debug toggle for needs telemetry diagnostics.
- Modify: `src/main/resources/Server/Tamework/Debug/TwDebugDefault.json`
  - Defaults the new needs telemetry toggle to false.
- Modify: `src/main/java/com/alechilles/alecstamework/commands/TameworkCommandRoot.java`
  - Registers `/tw debugneedstelemetry`.
- Create: `src/main/java/com/alechilles/alecstamework/commands/TameworkDebugNeedsTelemetryCommand.java`
  - Mirrors existing debug command style.
- Modify: `src/main/resources/telemetry/project.json`
  - Adds descriptor entries and allowed `details` context fields for needs diagnostic events.
- Modify: `wiki/Modder-Documentation/Testing-and-Diagnostics/Debugging-and-Debug-Commands.md`
  - Documents the command and privacy/rate-limit behavior.
- Modify: `CHANGELOG.md`
  - Adds player/admin-facing note for diagnostics.
- Test: `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsTelemetryDiagnosticsTest.java`
- Test: existing command/debug config tests if present; otherwise add a focused wiring test next to current debug command tests.

---

### Task 1: Portal Context Rollup Schema

**Files:**
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\migrations\117_add_issue_context_rollups.sql`

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE IF NOT EXISTS telemetry_event_context_field_stats (
  project_id text NOT NULL REFERENCES telemetry_projects(project_id) ON DELETE CASCADE,
  event_type text NOT NULL,
  event_name text NOT NULL,
  group_key text NOT NULL,
  field_key text NOT NULL,
  window_date date NOT NULL,
  occurrence_count bigint NOT NULL DEFAULT 0,
  distinct_value_count integer NOT NULL DEFAULT 0,
  top_values jsonb NOT NULL DEFAULT '[]'::jsonb,
  first_seen_at timestamptz NOT NULL,
  last_seen_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (project_id, event_type, event_name, group_key, field_key, window_date)
);

CREATE INDEX IF NOT EXISTS telemetry_event_context_field_stats_issue_idx
  ON telemetry_event_context_field_stats(project_id, event_type, event_name, group_key, field_key);

CREATE TABLE IF NOT EXISTS telemetry_issue_context_rollups_daily (
  id bigserial PRIMARY KEY,
  project_id text NOT NULL REFERENCES telemetry_projects(project_id) ON DELETE CASCADE,
  event_type text NOT NULL,
  event_name text NOT NULL,
  group_key text NOT NULL,
  field_key text NOT NULL,
  field_value text NOT NULL,
  bucket_date date NOT NULL,
  plugin_version text NULL,
  world_name text NULL,
  server_id text NULL,
  occurrence_count bigint NOT NULL DEFAULT 0,
  affected_sessions bigint NOT NULL DEFAULT 0,
  first_seen_at timestamptz NOT NULL,
  last_seen_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS telemetry_issue_context_rollups_daily_unique_idx
  ON telemetry_issue_context_rollups_daily(
    project_id,
    event_type,
    event_name,
    group_key,
    field_key,
    field_value,
    bucket_date,
    COALESCE(plugin_version, ''),
    COALESCE(world_name, ''),
    COALESCE(server_id, '')
  );

CREATE INDEX IF NOT EXISTS telemetry_issue_context_rollups_lookup_idx
  ON telemetry_issue_context_rollups_daily(project_id, event_type, event_name, group_key, field_key, occurrence_count DESC);
```

- [ ] **Step 2: Run migration tests or schema checks**

Run: `npm test -- tests/guardrails.test.ts`

Expected: PASS.

### Task 2: Portal Event Context Extraction

**Files:**
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\src\telemetry\event-context-fields.ts`
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\tests\telemetry-accepted-occurrence.test.ts`

- [ ] **Step 1: Add failing tests for scalar extraction**

```ts
import { describe, expect, it } from "vitest";
import { extractEventContextFields } from "../src/telemetry/event-context-fields.js";

describe("extractEventContextFields", () => {
  it("extracts bounded scalar details and skips arrays and objects", () => {
    const fields = extractEventContextFields(JSON.stringify({
      details: {
        reason: "no_water_target",
        resource: "water",
        count: 3,
        owned: true,
        tags: ["bad"],
        nested: { bad: true }
      }
    }));

    expect(fields).toEqual([
      { fieldKey: "reason", fieldValue: "no_water_target" },
      { fieldKey: "resource", fieldValue: "water" },
      { fieldKey: "count", fieldValue: "3" },
      { fieldKey: "owned", fieldValue: "true" }
    ]);
  });

  it("skips high-risk identifier-looking fields by default", () => {
    const fields = extractEventContextFields(JSON.stringify({
      details: {
        npcUuid: "6d2945c3-6bf0-3176-808d-0518464c1398",
        ownerId: "468be68b-684d-45e7-8b10-98a645b3fbba",
        reason: "cached_miss"
      }
    }));

    expect(fields).toEqual([{ fieldKey: "reason", fieldValue: "cached_miss" }]);
  });
});
```

- [ ] **Step 2: Implement extraction**

```ts
export interface EventContextField {
  fieldKey: string;
  fieldValue: string;
}

const MAX_CONTEXT_FIELDS = 12;
const MAX_KEY_LENGTH = 80;
const MAX_VALUE_LENGTH = 160;
const HIGH_RISK_FIELD = /(uuid|guid|owner|player|session|token|secret|key|email|name|x$|y$|z$|position|coord)/i;

export function extractEventContextFields(rawJson: string): EventContextField[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(rawJson);
  } catch {
    return [];
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return [];
  }
  const details = (parsed as { details?: unknown }).details;
  if (!details || typeof details !== "object" || Array.isArray(details)) {
    return [];
  }
  const fields: EventContextField[] = [];
  for (const [rawKey, rawValue] of Object.entries(details)) {
    if (fields.length >= MAX_CONTEXT_FIELDS) break;
    const fieldKey = normalizeFacetText(rawKey, MAX_KEY_LENGTH);
    const fieldValue = normalizeFacetValue(rawValue);
    if (!fieldKey || !fieldValue || HIGH_RISK_FIELD.test(fieldKey)) continue;
    fields.push({ fieldKey, fieldValue });
  }
  return fields;
}

function normalizeFacetValue(value: unknown): string | null {
  if (typeof value === "string") return normalizeFacetText(value, MAX_VALUE_LENGTH);
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  if (typeof value === "boolean") return String(value);
  return null;
}

function normalizeFacetText(value: string, maxLength: number): string | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  return trimmed.length <= maxLength ? trimmed : trimmed.slice(0, maxLength);
}
```

- [ ] **Step 3: Run test**

Run: `npm test -- tests/telemetry-accepted-occurrence.test.ts`

Expected: PASS.

### Task 3: Portal Context Rollup Writes

**Files:**
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\src\db\repositories\telemetry-occurrence-repo.ts`
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\tests\telemetry-occurrence-repo.test.ts`

- [ ] **Step 1: Add failing repository test**

Add a test that records an accepted event occurrence with:

```ts
rawJson: JSON.stringify({
  eventType: "error",
  eventName: "needs_seek_failed",
  fingerprint: "tamework.needs.seek.failed",
  details: {
    reason: "no_water_target",
    resource: "water"
  }
})
```

Assert one context rollup row for `reason=no_water_target` and one for `resource=water`, both under the same `event_type`, `event_name`, and `group_key`.

- [ ] **Step 2: Implement write path**

After `telemetry_occurrence_groups` upsert succeeds, call `extractEventContextFields(occurrence.rawJson)`. For each context field, upsert one daily rollup row:

```sql
INSERT INTO telemetry_issue_context_rollups_daily (
  project_id,
  event_type,
  event_name,
  group_key,
  field_key,
  field_value,
  bucket_date,
  plugin_version,
  world_name,
  server_id,
  occurrence_count,
  affected_sessions,
  first_seen_at,
  last_seen_at,
  updated_at
)
VALUES ($1,$2,$3,$4,$5,$6,COALESCE($7::date, CURRENT_DATE),$8,$9,$10,$11,$12,COALESCE($13, now()),COALESCE($13, now()),now())
ON CONFLICT (...) DO UPDATE SET
  occurrence_count = telemetry_issue_context_rollups_daily.occurrence_count + EXCLUDED.occurrence_count,
  affected_sessions = GREATEST(telemetry_issue_context_rollups_daily.affected_sessions, EXCLUDED.affected_sessions),
  last_seen_at = GREATEST(telemetry_issue_context_rollups_daily.last_seen_at, EXCLUDED.last_seen_at),
  updated_at = now()
```

Use `ON CONFLICT ON CONSTRAINT` only if the unique index is converted to a named constraint; otherwise use the same indexed key columns from `telemetry_issue_context_rollups_daily_unique_idx` in the conflict target.

- [ ] **Step 3: Run test**

Run: `npm test -- tests/telemetry-occurrence-repo.test.ts`

Expected: PASS and no duplicate rollups when the same occurrence is inserted twice.

### Task 4: Portal Context Breakdown Read API

**Files:**
- Create: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\src\db\repositories\telemetry-issue-context-repo.ts`
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\src\portal\types.ts`
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\src\index.ts`
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\shared\portal\contracts.ts`
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\tests\telemetry-issue-context-repo.test.ts`

- [ ] **Step 1: Add contract types**

```ts
export interface PortalIssueContextValue {
  value: string;
  occurrenceCount: number;
  affectedSessions: number;
  latestRelease: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
}

export interface PortalIssueContextBreakdown {
  fieldKey: string;
  label: string;
  totalOccurrences: number;
  values: PortalIssueContextValue[];
}
```

Add `contextBreakdowns: PortalIssueContextBreakdown[]` to `PortalIssueWorkspaceResponse`.

- [ ] **Step 2: Implement repository read**

`TelemetryIssueContextRepository.listIssueContextBreakdowns(projectId, issue, limit = 6)` should query `telemetry_issue_context_rollups_daily` by `project_id`, `event_type`, `event_name`, and `group_key`, group by `field_key`, and return top fields by occurrence count. For each field, return top values.

- [ ] **Step 3: Run test**

Run: `npm test -- tests/telemetry-issue-context-repo.test.ts`

Expected: PASS for `reason` and `resource` breakdowns on one issue.

### Task 5: Portal Issue Workspace UI

**Files:**
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\src\portal\routes\issue-routes.ts`
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\portal-ui\src\features\issues\issue-detail-pages.tsx`
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\portal-ui\src\styles\features\issues-workspace.css`
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\tests\portal-server-routes.test.ts`
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetryPlatform\portal-ui\src\features\issues\issue-detail-pages.test.tsx`

- [ ] **Step 1: Add route response test**

Assert `/api/projects/:projectId/issues/:issueId/workspace` includes:

```json
{
  "contextBreakdowns": [
    {
      "fieldKey": "reason",
      "label": "Reason",
      "totalOccurrences": 42,
      "values": [
        {
          "value": "no_water_target",
          "occurrenceCount": 42
        }
      ]
    }
  ]
}
```

- [ ] **Step 2: Add UI test**

Render `IssueDetailPage` with `contextBreakdowns` and assert it shows `Context Breakdowns`, `Reason`, and `no_water_target`.

- [ ] **Step 3: Implement route and UI**

In `issue-routes.ts`, load breakdowns in parallel with activity/GitHub link:

```ts
const [activityPreview, githubLink, contextBreakdowns] = await Promise.all([
  deps.telemetryIssueWorkflowRepo.listIssueActivity(projectId, issueId, activityLimit),
  deps.telemetryGitHubIssueLinkRepo.getIssueLink(projectId, issueId),
  deps.telemetryIssueContextRepo.listIssueContextBreakdowns(projectId, issue)
]);
```

Render a new panel after `IssueSignalsPanel`:

```tsx
function IssueContextBreakdownsPanel({ breakdowns }: { breakdowns: PortalIssueContextBreakdown[] }) {
  if (breakdowns.length === 0) return null;
  return (
    <section className="panel issue-workspace-panel">
      <PanelHeader eyebrow="Context Breakdowns" title="Event context grouped for this issue" />
      <div className="issue-context-breakdowns">
        {breakdowns.map((breakdown) => (
          <div className="issue-context-breakdown" key={breakdown.fieldKey}>
            <div className="issue-signal-group__title">{breakdown.label}</div>
            <DistributionList rows={breakdown.values.map((value) => ({
              label: value.value,
              count: value.occurrenceCount
            }))} />
          </div>
        ))}
      </div>
    </section>
  );
}
```

- [ ] **Step 4: Run portal checks**

Run:

```powershell
npm test -- tests/portal-server-routes.test.ts
npm test -- portal-ui/src/features/issues/issue-detail-pages.test.tsx
npm run check
```

Expected: PASS.

### Task 6: Alec's Telemetry Runtime Error Details

**Files:**
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\runtime\src\main\java\com\alechilles\alecstelemetry\project\TelemetryProjectDescriptor.java`
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\runtime\src\main\java\com\alechilles\alecstelemetry\core\TelemetryCoreEngine.java`
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\runtime\src\test\java\com\alechilles\alecstelemetry\project\TelemetryProjectDescriptorTest.java`
- Test: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\standalone\src\test\java\com\alechilles\alecstelemetry\runtime\TelemetryRuntimeServiceTest.java`

- [ ] **Step 1: Add failing descriptor test**

Add JSON with:

```json
"events": {
  "errors": {
    "enabled": true,
    "details": {
      "needs_seek_failed": {
        "allowedFields": {
          "reason": { "type": "string", "maxLength": 120 },
          "resource": { "type": "enum", "values": ["food", "water", "unknown"] }
        }
      }
    }
  }
}
```

Assert:

```java
assertEquals("no_water_target", descriptor.events().errors().sanitizeDetails(
        "needs_seek_failed",
        Map.of("reason", "no_water_target", "ignored", "drop")
).get("reason"));
assertFalse(descriptor.events().errors().sanitizeDetails(
        "needs_seek_failed",
        Map.of("ignored", "drop")
).containsKey("ignored"));
```

- [ ] **Step 2: Extend `EventTypeOptions`**

Change `EventTypeOptions` from:

```java
public record EventTypeOptions(boolean enabled) {
}
```

to:

```java
public record EventTypeOptions(boolean enabled,
                               @Nonnull Map<String, DetailRules> details) {

    @Nonnull
    public Map<String, Object> sanitizeDetails(@Nonnull String eventName, @Nullable Map<String, Object> rawDetails) {
        return sanitizeDetailMap(details, eventName, rawDetails);
    }
}
```

Update defaults to pass `Map.of()` for existing event type options.

- [ ] **Step 3: Parse error details from descriptor**

Extend the descriptor document class for `errors.details`, then normalize it through existing `normalizeDetailRules(...)`, matching the usage/performance path.

- [ ] **Step 4: Store sanitized error details**

In `TelemetryCoreEngine.recordErrorWithContext`, replace:

```java
LinkedHashMap<String, Object> details = new LinkedHashMap<>();
putBreadcrumbDetails(project, details);
```

with:

```java
LinkedHashMap<String, Object> details = new LinkedHashMap<>(
        project.events().errors().sanitizeDetails(eventName, normalizedContext.details())
);
putBreadcrumbDetails(project, details);
```

This preserves breadcrumbs while adding descriptor-approved error details.

- [ ] **Step 5: Run runtime tests**

Run:

```powershell
.\mvnw.cmd -pl runtime,standalone test
```

Expected: PASS.

### Task 7: Alec's Telemetry Runtime Documentation

**Files:**
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\docs\project-descriptor.md`
- Modify: `C:\Users\22ale\AppData\Roaming\Hytale\Modding\AlecsTelemetry\docs\hosted-ingest-contract.md`

- [ ] **Step 1: Document the contract**

Add a section:

```md
## Event Context Details

Generic event `details` are presented by the hosted portal as Event Context. Scalar, bounded, low-cardinality `details` fields may be aggregated into Context Breakdowns. Modders do not need platform schema changes for each field. The portal stores raw payloads and may aggregate safe fields into key/value rollups for issue detail pages.

Avoid sending player identifiers, raw UUIDs, secrets, exact coordinates, or free-form messages as context breakdown fields. Use stable reason codes, resource types, role IDs, entity types, and coarse buckets.
```

- [ ] **Step 2: Run runtime tests**

Run: `.\mvnw.cmd test`

Expected: PASS.

### Task 8: Tamework Needs Telemetry Diagnostics

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsTelemetryDiagnostics.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsSeekDiagnostics.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/NeedsConsumeDiagnostics.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionNeedsConsumeService.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/progression/NeedsTelemetryDiagnosticsTest.java`

- [ ] **Step 1: Add failing tests**

Test that:

```java
assertEquals("needs_seek_failed", NeedsTelemetryDiagnostics.EventNames.NEEDS_SEEK_FAILED);
assertEquals("tamework.needs.seek.failed", NeedsTelemetryDiagnostics.Fingerprints.NEEDS_SEEK_FAILED);
```

Also test `needsBucket(0.12)` returns `"0-25"` and `needsBucket(null)` returns `"unknown"`.

- [ ] **Step 2: Implement diagnostics class**

Core behavior:

```java
public final class NeedsTelemetryDiagnostics {
    public static final class EventNames {
        public static final String NEEDS_SEEK_FAILED = "needs_seek_failed";
        public static final String NEEDS_CONSUME_FAILED = "needs_consume_failed";
    }

    public static final class Fingerprints {
        public static final String NEEDS_SEEK_FAILED = "tamework.needs.seek.failed";
        public static final String NEEDS_CONSUME_FAILED = "tamework.needs.consume.failed";
    }

    public static String needsBucket(@Nullable Double ratio) {
        if (ratio == null || !Double.isFinite(ratio)) return "unknown";
        double clamped = Math.max(0.0d, Math.min(1.0d, ratio));
        if (clamped <= 0.25d) return "0-25";
        if (clamped <= 0.50d) return "26-50";
        if (clamped <= 0.75d) return "51-75";
        return "76-100";
    }
}
```

Add `recordSeekFailure(...)` and `recordConsumeFailure(...)` methods that call:

```java
TameworkTelemetryEvents.recordErrorIfAvailable(
        EventNames.NEEDS_SEEK_FAILED,
        null,
        TameworkTelemetryEvents.featureContext("needs", "needs_seek", "runtime")
                .severity("warning")
                .fingerprint(Fingerprints.NEEDS_SEEK_FAILED)
                .entityType(roleId)
                .target(resourceType)
                .detail("reason", detail)
                .detail("resource", resourceType)
                .detail("roleId", roleId == null || roleId.isBlank() ? "unknown" : roleId)
                .detail("cacheHit", cacheHit)
                .detail("needBucket", needsBucket(currentRatio))
                .build()
);
```

Use a `ConcurrentHashMap<String, Long>` to throttle by `eventName|roleId|resource|reason` for at least 5 minutes.

- [ ] **Step 3: Wire seek failures**

In `NeedsSeekDiagnostics.maybeLog`, call telemetry for non-`target_found` results after the local log throttle logic computes the signature. Do not include exact target coordinates in telemetry.

- [ ] **Step 4: Wire consume failures**

In `NeedsConsumeDiagnostics.maybeLogConsume`, call telemetry for `LogLevel.INFO` failure outcomes where `reason` is not `success` and not blank. Pass consumed item counts and gain buckets, not exact entity/player identifiers.

- [ ] **Step 5: Run tests**

Run: `.\mvnw.cmd -Dtest=NeedsTelemetryDiagnosticsTest test`

Expected: PASS.

### Task 9: Tamework Debug Toggle and Descriptor

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwDebugConfig.java`
- Modify: `src/main/resources/Server/Tamework/Debug/TwDebugDefault.json`
- Modify: `src/main/java/com/alechilles/alecstamework/commands/TameworkCommandRoot.java`
- Create: `src/main/java/com/alechilles/alecstamework/commands/TameworkDebugNeedsTelemetryCommand.java`
- Modify: `src/main/resources/telemetry/project.json`
- Test: command/config wiring test near existing debug command tests

- [ ] **Step 1: Add config field**

Add a boolean field named `NeedsTelemetryDiagnostics` or local-style equivalent next to existing needs debug toggles. Default false in `TwDebugDefault.json`.

- [ ] **Step 2: Add command**

Implement `/tw debugneedstelemetry [on|off]` following the existing debug command classes. The command should only toggle telemetry diagnostics, not local `debugneedsseek` or `debugneedsconsume`.

- [ ] **Step 3: Update telemetry descriptor**

Add error details for:

```json
"needs_seek_failed": {
  "allowedFields": {
    "reason": { "type": "string", "maxLength": 120 },
    "resource": { "type": "enum", "values": ["food", "water", "unknown"] },
    "roleId": { "type": "string", "maxLength": 160 },
    "cacheHit": { "type": "boolean" },
    "needBucket": { "type": "enum", "values": ["0-25", "26-50", "51-75", "76-100", "unknown"] }
  }
}
```

This relies on Task 6 so error telemetry can carry descriptor-sanitized details while remaining issue-producing.

- [ ] **Step 4: Run Tamework tests**

Run:

```powershell
.\mvnw.cmd test
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Expected: all tests pass; grep has no unsafe runtime-path matches.

### Task 10: End-to-End Validation

**Files:**
- No new files; use all three repos.

- [ ] **Step 1: Seed or ingest a sample event**

Use the platform test helpers or a local HTTP ingest request with a payload:

```json
{
  "eventType": "error",
  "eventName": "needs_seek_failed",
  "fingerprint": "tamework.needs.seek.failed",
  "severity": "warning",
  "details": {
    "reason": "no_water_target",
    "resource": "water",
    "roleId": "Tamed_Chicken",
    "needBucket": "0-25"
  }
}
```

- [ ] **Step 2: Verify grouping**

Open the issue page and confirm there is one `needs_seek_failed` issue for the stable fingerprint, not one issue per reason/resource.

- [ ] **Step 3: Verify breakdowns**

Confirm the issue page shows:

- Issue Signals: releases, sources, severities, worlds, servers
- Context Breakdowns: reason, resource, roleId, needBucket
- Raw Payload remains available but is not the primary UI

- [ ] **Step 4: Verify graceful fallback**

Disable telemetry in Tamework settings and trigger local seek/consume diagnostics. Expected: no gameplay error and local debug commands still work.

## Self-Review

- Spec coverage: covers portal arbitrary `details` context support, key/value aggregate storage, Tamework needs diagnostics, issue grouping by stable fingerprint, Context Breakdowns UI, docs, and fallback behavior.
- Placeholder scan: no task uses open-ended placeholder instructions; each task names files, commands, and expected behavior.
- Type consistency: `PortalIssueContextBreakdown`, `PortalIssueContextValue`, `needs_seek_failed`, `needs_consume_failed`, and stable fingerprints are named consistently across tasks.
