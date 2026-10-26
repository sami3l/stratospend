"use client";

import type { CloudAccount } from "@/lib/cloud-accounts";
import type { CloudResource } from "@/lib/cloud-resources";
import { resourceStringFields } from "@/lib/cloud-resource-form";
import { useCreateCloudResource } from "@/hooks/use-create-cloud-resource";
import { FieldError, ResourceDetailFields, ResourceFormDialog, fieldAttributes } from "./resource-form-dialog";

type Props = {
  accounts: CloudAccount[];
  onClose: () => void;
  onCreated: (resource: CloudResource) => void;
};

const idPrefix = "create-resource";

export function CreateCloudResourceDialog({ accounts, onClose, onCreated }: Props) {
  const { values, saving, feedback, changeField, submit } = useCreateCloudResource();
  const { errors } = feedback;

  return <ResourceFormDialog idPrefix={idPrefix} title="Add cloud resource"
    description="Register a resource under an existing cloud account. All fields are required."
    formLabel="Create cloud resource" submitLabel="Create resource" fields={Object.keys(values)}
    saving={saving} feedback={feedback} errorHeading={feedback.status === 409 ? "Resource already exists" : undefined}
    submit={submit} onSaved={onCreated} onClose={onClose}>
    <div className="create-field create-field-wide">
      <label htmlFor={`${idPrefix}-cloudAccountId`}>Cloud account</label>
      <select {...fieldAttributes(idPrefix, "cloudAccountId", errors)} required value={values.cloudAccountId}
        onChange={(event) => changeField("cloudAccountId", event.target.value)}>
        <option value="">Select a cloud account</option>
        {accounts.map((account) => <option key={account.id} value={account.id}>{account.name}</option>)}
      </select>
      <FieldError idPrefix={idPrefix} field="cloudAccountId" errors={errors} />
    </div>
    <div className="create-field create-field-wide">
      <label htmlFor={`${idPrefix}-externalResourceId`}>{resourceStringFields.externalResourceId.label}</label>
      <input {...fieldAttributes(idPrefix, "externalResourceId", errors)} required
        maxLength={resourceStringFields.externalResourceId.maxLength}
        autoCapitalize="none" autoCorrect="off" spellCheck={false}
        value={values.externalResourceId} onChange={(event) => changeField("externalResourceId", event.target.value)} />
      <FieldError idPrefix={idPrefix} field="externalResourceId" errors={errors} />
    </div>
    <ResourceDetailFields idPrefix={idPrefix} values={values} errors={errors} changeField={changeField} />
  </ResourceFormDialog>;
}
