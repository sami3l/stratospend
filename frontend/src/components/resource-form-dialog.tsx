"use client";

import { useEffect, useRef, type ReactNode } from "react";
import type { ResourceFormFeedback } from "@/hooks/use-resource-form";
import {
  resourceCategories, resourceStatuses, resourceStringFields, type ResourceDetailsValues,
} from "@/lib/cloud-resource-form";

type Errors = Record<string, string>;

export function fieldAttributes(idPrefix: string, field: string, errors: Errors) {
  return {
    id: `${idPrefix}-${field}`, name: field,
    "aria-invalid": Boolean(errors[field]),
    "aria-describedby": errors[field] ? `${idPrefix}-${field}-error` : undefined,
  };
}

export function FieldError({ idPrefix, field, errors }: { idPrefix: string; field: string; errors: Errors }) {
  return errors[field] ? <p className="field-error" id={`${idPrefix}-${field}-error`}>{errors[field]}</p> : null;
}

type DialogProps<R> = {
  idPrefix: string;
  title: string;
  description: string;
  formLabel: string;
  submitLabel: string;
  /** Fields rendered by the form; server errors for any other field are listed in the alert. */
  fields: string[];
  saving: boolean;
  feedback: ResourceFormFeedback;
  errorHeading?: string;
  submit: () => Promise<R | null>;
  onSaved: (result: R) => void;
  onClose: () => void;
  children: ReactNode;
};

export function ResourceFormDialog<R>({
  idPrefix, title, description, formLabel, submitLabel, fields, saving, feedback, errorHeading, submit, onSaved, onClose, children,
}: DialogProps<R>) {
  const dialog = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const element = dialog.current!;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    element.showModal();
    element.querySelector<HTMLElement>("fieldset input, fieldset select")?.focus();
    return () => { element.close(); document.body.style.overflow = previousOverflow; };
  }, []);

  useEffect(() => {
    if (feedback.attempt > 0) {
      const target = dialog.current?.querySelector<HTMLElement>('[aria-invalid="true"]')
        ?? dialog.current?.querySelector<HTMLElement>('[role="alert"]');
      target?.focus();
    }
  }, [feedback.attempt]);

  function close() {
    if (saving) return;
    dialog.current?.close();
    onClose();
  }

  const extraErrors = Object.entries(feedback.errors).filter(([field]) => !fields.includes(field));

  return <dialog ref={dialog} className="resource-dialog" aria-modal="true" aria-labelledby={`${idPrefix}-title`}
    onCancel={(event) => { event.preventDefault(); close(); }}>
    <h2 id={`${idPrefix}-title`}>{title}</h2>
    <p className="dialog-description">{description}</p>
    <form noValidate aria-label={formLabel} aria-busy={saving} onSubmit={async (event) => {
      event.preventDefault();
      const saved = await submit();
      // Close the modal first so the caller can move focus back to the page.
      if (saved) { dialog.current?.close(); onSaved(saved); }
    }}>
      {feedback.message && <div className="dialog-error" role="alert" tabIndex={-1}>
        {errorHeading && <strong>{errorHeading}</strong>}
        <p>{feedback.message}</p>
        {extraErrors.length > 0 && <ul>{extraErrors.map(([field, message]) => <li key={field}>{field}: {message}</li>)}</ul>}
      </div>}
      <fieldset disabled={saving} className="resource-create-fields">
        <legend className="visually-hidden">Resource information</legend>
        {children}
      </fieldset>
      <div className="dialog-actions">
        <button type="button" className="inventory-button" disabled={saving} onClick={close}>Cancel</button>
        <button type="submit" className="inventory-button primary-action" disabled={saving}>{saving ? "Saving…" : submitLabel}</button>
      </div>
      {saving && <p role="status">Saving resource…</p>}
    </form>
  </dialog>;
}

type DetailFieldsProps = {
  idPrefix: string;
  values: ResourceDetailsValues;
  errors: Errors;
  // Select options come from the category and status label maps, so every value is a valid enum.
  changeField: (field: keyof ResourceDetailsValues, value: string) => void;
};

/** The fields that stay editable after registration: name, category, service, region and status. */
export function ResourceDetailFields({ idPrefix, values, errors, changeField }: DetailFieldsProps) {
  const attributes = (field: string) => fieldAttributes(idPrefix, field, errors);
  return <>
    <div className="create-field create-field-wide">
      <label htmlFor={`${idPrefix}-name`}>{resourceStringFields.name.label}</label>
      <input {...attributes("name")} required maxLength={resourceStringFields.name.maxLength} spellCheck
        value={values.name} onChange={(event) => changeField("name", event.target.value)} />
      <FieldError idPrefix={idPrefix} field="name" errors={errors} />
    </div>
    <div className="create-field">
      <label htmlFor={`${idPrefix}-category`}>Category</label>
      <select {...attributes("category")} required value={values.category}
        onChange={(event) => changeField("category", event.target.value)}>
        <option value="">Select a category</option>
        {Object.entries(resourceCategories).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select>
      <FieldError idPrefix={idPrefix} field="category" errors={errors} />
    </div>
    {(["providerService", "region"] as const).map((field) => <div className="create-field" key={field}>
      <label htmlFor={`${idPrefix}-${field}`}>{resourceStringFields[field].label}</label>
      <input {...attributes(field)} required maxLength={resourceStringFields[field].maxLength}
        pattern={field === "region" ? "[a-z0-9-]+" : undefined}
        placeholder={field === "region" ? "e.g. eu-west-1 or global" : undefined}
        value={values[field]} onChange={(event) => changeField(field, event.target.value)} />
      <FieldError idPrefix={idPrefix} field={field} errors={errors} />
    </div>)}
    <div className="create-field">
      <label htmlFor={`${idPrefix}-status`}>Status</label>
      <select {...attributes("status")} required value={values.status}
        onChange={(event) => changeField("status", event.target.value)}>
        <option value="">Select a status</option>
        {Object.entries(resourceStatuses).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select>
      <FieldError idPrefix={idPrefix} field="status" errors={errors} />
    </div>
  </>;
}
