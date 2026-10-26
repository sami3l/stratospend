"use client";

import type { CloudResource } from "@/lib/cloud-resources";
import { useUpdateCloudResource } from "@/hooks/use-update-cloud-resource";
import { ResourceDetailFields, ResourceFormDialog } from "./resource-form-dialog";

type Props = {
  resource: CloudResource;
  onClose: () => void;
  /** Receives the saved resource, or the unchanged resource when there was nothing to save. */
  onSaved: (resource: CloudResource) => void;
};

export function EditCloudResourceDialog({ resource, onClose, onSaved }: Props) {
  const { values, saving, feedback, changeField, submit } = useUpdateCloudResource(resource);

  return <ResourceFormDialog idPrefix="edit-resource" title="Edit cloud resource"
    description="Update the descriptive fields. The cloud account and external resource ID cannot be changed after registration."
    formLabel="Edit cloud resource" submitLabel="Save changes" fields={Object.keys(values)}
    saving={saving} feedback={feedback} submit={submit} onSaved={onSaved} onClose={onClose}>
    <dl className="resource-fixed-fields create-field-wide">
      <div><dt>Cloud account</dt><dd>{resource.cloudAccountName}</dd></div>
      <div><dt>External resource ID</dt><dd><code>{resource.externalResourceId}</code></dd></div>
    </dl>
    <ResourceDetailFields idPrefix="edit-resource" values={values} errors={feedback.errors} changeField={changeField} />
  </ResourceFormDialog>;
}
