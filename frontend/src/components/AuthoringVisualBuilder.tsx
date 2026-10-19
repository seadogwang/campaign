import type { ReactElement, ReactNode } from 'react'
import type { AuthoringField } from '../authoring/intent'
import type {
  BasicsForm,
  GovernanceFormModel,
  MessageFormModel,
  PromotionFormModel,
  ScheduleFormModel,
  SegmentFormModel,
  VisualBuilderState,
} from '../authoring/visual'
import { SCHEDULE_FORMS, type ScheduleForm } from '../contracts/summaries'

interface FieldSpec {
  readonly field: AuthoringField
  readonly title: string
  readonly description: string
  readonly render: () => ReactNode
}

/**
 * The Visual Builder authoring surface: typed forms for Campaign basics,
 * Segment, Message, Promotion, Journey, Schedule and Governance. Editing here
 * produces exactly the same Proposal operation types as the conversation
 * surface.
 */
export function AuthoringVisualBuilder({
  state,
  onChange,
}: {
  readonly state: VisualBuilderState
  readonly onChange: (state: VisualBuilderState) => void
}): ReactElement {
  const patch = <K extends keyof VisualBuilderState>(
    key: K,
    value: VisualBuilderState[K],
  ): void => {
    onChange({ ...state, [key]: value })
  }
  const setIncluded = (field: AuthoringField, included: boolean): void => {
    onChange({ ...state, included: { ...state.included, [field]: included } })
  }

  const specs: readonly FieldSpec[] = [
    {
      field: 'basics',
      title: 'Campaign basics',
      description: 'Locale is submitted through the Proposal client context. Display name is local editor state only.',
      render: () => (
        <BasicsFields value={state.basics} onChange={(value) => patch('basics', value)} />
      ),
    },
    {
      field: 'segment',
      title: 'Segment',
      description: 'The declared Segment DSL wire document. Derived envelope hashes are server-owned.',
      render: () => (
        <SegmentFields value={state.segment} onChange={(value) => patch('segment', value)} />
      ),
    },
    {
      field: 'message',
      title: 'Message',
      description: 'Message template reference, channel and variables. No template body is carried.',
      render: () => (
        <MessageFields value={state.message} onChange={(value) => patch('message', value)} />
      ),
    },
    {
      field: 'promotion',
      title: 'Promotion',
      description: 'Reward reference and quantity.',
      render: () => (
        <PromotionFields value={state.promotion} onChange={(value) => patch('promotion', value)} />
      ),
    },
    {
      field: 'journey',
      title: 'Journey',
      description:
        'Unavailable in this bounded form: a Journey is a set of Flow node components plus a separate '
        + 'topology, so it is authored in the conversation and never as one capability payload.',
      render: () => <JourneyUnavailable />,
    },
    {
      field: 'schedule',
      title: 'Schedule',
      description: 'Immediate, one-time or daily window in a canonical IANA zone.',
      render: () => (
        <ScheduleFields value={state.schedule} onChange={(value) => patch('schedule', value)} />
      ),
    },
    {
      field: 'governance',
      title: 'Governance',
      description: 'Recipient, reward-grant and face-value limits with their currency.',
      render: () => (
        <GovernanceFields value={state.governance} onChange={(value) => patch('governance', value)} />
      ),
    },
  ]

  return (
    <div className="surface">
      <p className="surface__boundary" role="note">
        Visual edits are local unsaved state until you submit. They produce the same Proposal
        operations as the conversation surface.
      </p>
      {specs.map((spec) => (
        <fieldset className="form-section" key={spec.field}>
          <legend>{spec.title}</legend>
          <p className="form-section__description">{spec.description}</p>
          <label className="field field--inline">
            <input
              type="checkbox"
              checked={state.included[spec.field]}
              onChange={(event) => setIncluded(spec.field, event.target.checked)}
              aria-label={`Include ${spec.title} in the next Proposal`}
            />
            <span>Include in the next Proposal</span>
          </label>
          <div aria-disabled={state.included[spec.field] ? undefined : true}>
            {spec.render()}
          </div>
        </fieldset>
      ))}
    </div>
  )
}

function TextField({
  id,
  label,
  value,
  onChange,
  type = 'text',
}: {
  readonly id: string
  readonly label: string
  readonly value: string
  readonly onChange: (value: string) => void
  readonly type?: 'text' | 'number'
}): ReactElement {
  return (
    <label className="field" htmlFor={id}>
      <span className="field__label">{label}</span>
      <input
        id={id}
        className="field__input"
        type={type}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}

function BasicsFields({ value, onChange }: { readonly value: BasicsForm; readonly onChange: (value: BasicsForm) => void }): ReactElement {
  return (
    <>
      <TextField id="basics-locale" label="Locale (submitted)" value={value.locale} onChange={(locale) => onChange({ ...value, locale })} />
      <TextField id="basics-timezone" label="Default timezone (used by Schedule)" value={value.timezone} onChange={(timezone) => onChange({ ...value, timezone })} />
      <TextField id="basics-name" label="Display name (local only, not submitted)" value={value.displayName} onChange={(displayName) => onChange({ ...value, displayName })} />
    </>
  )
}

function SegmentFields({ value, onChange }: { readonly value: SegmentFormModel; readonly onChange: (value: SegmentFormModel) => void }): ReactElement {
  return (
    <label className="field" htmlFor="segment-dsl">
      <span className="field__label">Segment DSL wire document</span>
      <textarea
        id="segment-dsl"
        className="field__input field__input--area"
        rows={4}
        value={value.dslWire}
        onChange={(event) => onChange({ dslWire: event.target.value })}
      />
    </label>
  )
}

function MessageFields({ value, onChange }: { readonly value: MessageFormModel; readonly onChange: (value: MessageFormModel) => void }): ReactElement {
  return (
    <>
      <TextField id="message-reference" label="Template reference code" value={value.referenceCode} onChange={(referenceCode) => onChange({ ...value, referenceCode })} />
      <label className="field" htmlFor="message-channel">
        <span className="field__label">Channel</span>
        <select
          id="message-channel"
          className="field__input"
          value={value.channel}
          onChange={(event) => onChange({ ...value, channel: event.target.value })}
        >
          {['EMAIL', 'SMS', 'PUSH', 'IN_APP'].map((channel) => (
            <option key={channel} value={channel}>
              {channel}
            </option>
          ))}
        </select>
      </label>
      <TextField id="message-variables" label="Variables (comma separated)" value={value.variables} onChange={(variables) => onChange({ ...value, variables })} />
    </>
  )
}

function PromotionFields({ value, onChange }: { readonly value: PromotionFormModel; readonly onChange: (value: PromotionFormModel) => void }): ReactElement {
  return (
    <>
      <TextField id="promotion-reference" label="Reward reference code" value={value.referenceCode} onChange={(referenceCode) => onChange({ ...value, referenceCode })} />
      <TextField id="promotion-quantity" label="Quantity" type="number" value={value.quantity} onChange={(quantity) => onChange({ ...value, quantity })} />
    </>
  )
}

/**
 * The Journey form is deliberately unavailable.
 *
 * A Journey is a bounded set of accepted Flow node components plus one separate topology, which this
 * bounded form cannot declare, so it renders no input and submits nothing: the surface never fabricates a
 * component or a mutation for it.
 */
function JourneyUnavailable(): ReactElement {
  return (
    <p className="field__note" data-testid="journey-unavailable">
      Author this Journey in the conversation: it needs real Flow nodes and their topology, and this form
      never emits a fake component.
    </p>
  )
}

function ScheduleFields({ value, onChange }: { readonly value: ScheduleFormModel; readonly onChange: (value: ScheduleFormModel) => void }): ReactElement {
  return (
    <>
      <label className="field" htmlFor="schedule-form">
        <span className="field__label">Form</span>
        <select
          id="schedule-form"
          className="field__input"
          value={value.form}
          onChange={(event) => onChange({ ...value, form: event.target.value as ScheduleForm })}
        >
          {SCHEDULE_FORMS.map((form) => (
            <option key={form} value={form}>
              {form}
            </option>
          ))}
        </select>
      </label>
      <TextField id="schedule-zone" label="Zone (IANA)" value={value.zoneId} onChange={(zoneId) => onChange({ ...value, zoneId })} />
      {value.form === 'ONCE' ? (
        <TextField id="schedule-run-at" label="Run at (ISO instant)" value={value.runAt} onChange={(runAt) => onChange({ ...value, runAt })} />
      ) : null}
      {value.form === 'DAILY' ? (
        <>
          <TextField id="schedule-local-time" label="Local time (HH:MM)" value={value.localTime} onChange={(localTime) => onChange({ ...value, localTime })} />
          <TextField id="schedule-start" label="Start date (YYYY-MM-DD)" value={value.startDate} onChange={(startDate) => onChange({ ...value, startDate })} />
          <TextField id="schedule-end" label="End date (YYYY-MM-DD)" value={value.endDate} onChange={(endDate) => onChange({ ...value, endDate })} />
        </>
      ) : null}
    </>
  )
}

function GovernanceFields({ value, onChange }: { readonly value: GovernanceFormModel; readonly onChange: (value: GovernanceFormModel) => void }): ReactElement {
  return (
    <>
      <TextField id="governance-recipients" label="Max recipients" type="number" value={value.maxRecipients} onChange={(maxRecipients) => onChange({ ...value, maxRecipients })} />
      <TextField id="governance-grants" label="Max reward grants" type="number" value={value.maxRewardGrants} onChange={(maxRewardGrants) => onChange({ ...value, maxRewardGrants })} />
      <TextField id="governance-face-value" label="Max reward face value" value={value.maxRewardFaceValue} onChange={(maxRewardFaceValue) => onChange({ ...value, maxRewardFaceValue })} />
      <TextField id="governance-currency" label="Currency" value={value.currency} onChange={(currency) => onChange({ ...value, currency })} />
    </>
  )
}