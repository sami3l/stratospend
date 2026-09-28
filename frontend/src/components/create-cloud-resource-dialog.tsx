"use client";

import { useEffect, useRef } from "react";
import type { CloudAccount } from "@/lib/cloud-accounts";
import type { CloudResource, ResourceCategory, ResourceStatus } from "@/lib/cloud-resources";
import { resourceCategories, resourceStatuses, resourceStringFields } from "@/lib/cloud-resource-form";
import { useCreateCloudResource } from "@/hooks/use-create-cloud-resource";

type Props = {
  accounts: CloudAccount[];
  onClose: () => void;
  onCreated: (resource: CloudResource) => void;
};

export function CreateCloudResourceDialog({ accounts, onClose, onCreated }: Props) {
  const dialog = useRef<HTMLDialogElement>(null);
  const { values, saving, feedback, changeField, submit } = useCreateCloudResource();

  useEffect(() => {
    const element = dialog.current!;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    element.showModal();
    element.querySelector("select")?.focus();
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

  function fieldAttributes(field: string) {
    return {
      id: `create-resource-${field}`, name: field,
      "aria-invalid": Boolean(feedback.errors[field]),
      "aria-describedby": feedback.errors[field] ? `create-resource-${field}-error` : undefined,
    };
  }

  function fieldError(field: string) {
    return feedback.errors[field] && <p className="field-error" id={`create-resource-${field}-error`}>{feedback.errors[field]}</p>;
  }

  const extraErrors = Object.entries(feedback.errors).filter(([field]) => !Object.hasOwn(values, field));

  return <dialog ref={dialog} className="resource-dialog" aria-modal="true" aria-labelledby="create-resource-title"
    onCancel={(event) => { event.preventDefault(); close(); }}>
    <h2 id="create-resource-title">Add cloud resource</h2>
    <p className="dialog-description">Register a resource under an existing cloud account. All fields are required.</p>
    <form noValidate aria-label="Create cloud resource" aria-busy={saving} onSubmit={async (event) => {
      event.preventDefault();
      const created = await submit();
      if (created) { dialog.current?.close(); onCreated(created); }
    }}>
      {feedback.message && <div className="dialog-error" role="alert" tabIndex={-1}>
        {feedback.status === 409 && <strong>Resource already exists</strong>}
        <p>{feedback.message}</p>
        {extraErrors.length > 0 && <ul>{extraErrors.map(([field, message]) => <li key={field}>{field}: {message}</li>)}</ul>}
      </div>}
      <fieldset disabled={saving} className="resource-create-fields">
        <legend className="visually-hidden">Resource information</legend>
        <div className="create-field create-field-wide">
          <label htmlFor="create-resource-cloudAccountId">Cloud account</label>
          <select {...fieldAttributes("cloudAccountId")} required value={values.cloudAccountId}
            onChange={(event) => changeField("cloudAccountId", event.target.value)}>
            <option value="">Select a cloud account</option>
            {accounts.map((account) => <option key={account.id} value={account.id}>{account.name}</option>)}
          </select>
          {fieldError("cloudAccountId")}
        </div>
        {(["externalResourceId", "name"] as const).map((field) => <div className="create-field create-field-wide" key={field}>
          <label htmlFor={`create-resource-${field}`}>{resourceStringFields[field].label}</label>
          <input {...fieldAttributes(field)} required maxLength={resourceStringFields[field].maxLength}
            autoCapitalize={field === "externalResourceId" ? "none" : undefined}
            autoCorrect={field === "externalResourceId" ? "off" : undefined} spellCheck={field !== "externalResourceId"}
            value={values[field]} onChange={(event) => changeField(field, event.target.value)} />
          {fieldError(field)}
        </div>)}
        <div className="create-field">
          <label htmlFor="create-resource-category">Category</label>
          <select {...fieldAttributes("category")} required value={values.category}
            onChange={(event) => changeField("category", event.target.value as ResourceCategory)}>
            <option value="">Select a category</option>
            {Object.entries(resourceCategories).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
          {fieldError("category")}
        </div>
        {(["providerService", "region"] as const).map((field) => <div className="create-field" key={field}>
          <label htmlFor={`create-resource-${field}`}>{resourceStringFields[field].label}</label>
          <input {...fieldAttributes(field)} required maxLength={resourceStringFields[field].maxLength}
            pattern={field === "region" ? "[a-z0-9-]+" : undefined}
            placeholder={field === "region" ? "e.g. eu-west-1 or global" : undefined}
            value={values[field]} onChange={(event) => changeField(field, event.target.value)} />
          {fieldError(field)}
        </div>)}
        <div className="create-field">
          <label htmlFor="create-resource-status">Status</label>
          <select {...fieldAttributes("status")} required value={values.status}
            onChange={(event) => changeField("status", event.target.value as ResourceStatus)}>
            <option value="">Select a status</option>
            {Object.entries(resourceStatuses).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
          {fieldError("status")}
        </div>
      </fieldset>
      <div className="dialog-actions">
        <button type="button" className="inventory-button" disabled={saving} onClick={close}>Cancel</button>
        <button type="submit" className="inventory-button primary-action" disabled={saving}>{saving ? "Saving…" : "Create resource"}</button>
      </div>
      {saving && <p role="status">Saving resource…</p>}
    </form>
  </dialog>;
}
