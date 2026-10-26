"use client";

import { useCallback, useRef, useState } from "react";
import Link from "next/link";
import { useLoad } from "@/hooks/use-load";
import { getCloudResource, type CloudResource } from "@/lib/cloud-resources";
import {
  formatTimestamp, providerLabels as providers, resourceCategories as categories, resourceStatuses as statuses,
} from "@/lib/cloud-resource-form";
import { EditCloudResourceDialog } from "./edit-cloud-resource-dialog";

function parseResourceId(value: string) {
  const id = Number(value);
  return /^[1-9]\d*$/.test(value) && Number.isSafeInteger(id) ? id : null;
}

function Heading({ title }: { title: string }) {
  return <>
    <Link className="back-link" href="/resources">← Back to resources</Link>
    <header className="page-heading">
      <div><p className="eyebrow">CLOUD RESOURCE</p><h1>{title}</h1></div>
    </header>
  </>;
}

function NotFound() {
  return <>
    <Heading title="Resource not found" />
    <div className="state">
      <strong>No resource exists with this ID</strong>
      <p>Check the link, or return to the <Link href="/resources">resource inventory</Link>.</p>
    </div>
  </>;
}

export function CloudResourceDetails({ id }: { id: string }) {
  const resourceId = parseResourceId(id);
  // An invalid ID never mounts the loader, so it sends no request. The key resets saved edits per resource.
  return resourceId === null ? <NotFound /> : <ResourceDetailsLoader key={resourceId} id={resourceId} />;
}

function ResourceDetailsLoader({ id }: { id: number }) {
  const loadResource = useCallback(() => getCloudResource(id), [id]);
  const resource = useLoad(loadResource, "Unable to load cloud resource");
  // The update response is the authoritative resource, so it replaces the loaded one without a refetch.
  const [saved, setSaved] = useState<CloudResource>();
  const [editing, setEditing] = useState(false);
  const [success, setSuccess] = useState("");
  const editButton = useRef<HTMLButtonElement>(null);

  if (resource.status === "error" && resource.httpStatus === 404) return <NotFound />;
  if (resource.status !== "success") {
    return <>
      <Heading title="Resource details" />
      {resource.status === "loading" ? <div className="state" role="status">Loading cloud resource…</div>
        : <div className="error" role="alert">
          <p>{resource.message}</p>
          <button className="inventory-button" onClick={resource.retry}>Retry</button>
        </div>}
    </>;
  }

  const data = saved ?? resource.data;

  function closeEdit() {
    setEditing(false);
    editButton.current?.focus();
  }

  return <>
    <Heading title={data.name} />
    <div className="resource-details-actions">
      <button ref={editButton} className="inventory-button primary-action"
        onClick={() => { setSuccess(""); setEditing(true); }}>Edit resource</button>
    </div>
    {success && <p className="inventory-success" role="status">{success}</p>}
    {editing && <EditCloudResourceDialog resource={data} onClose={closeEdit} onSaved={(updated) => {
      closeEdit();
      if (updated === data) {
        setSuccess("No changes to save.");
      } else {
        setSaved(updated);
        setSuccess(`Resource “${updated.name}” updated.`);
      }
    }} />}
    <dl className="resource-details">
      <div className="resource-detail-wide"><dt>External resource ID</dt><dd><code>{data.externalResourceId}</code></dd></div>
      <div><dt>Cloud account</dt><dd>{data.cloudAccountName}</dd></div>
      <div><dt>Provider</dt><dd><span className={`provider ${data.provider.toLowerCase()}`}>{providers[data.provider]}</span></dd></div>
      <div><dt>Category</dt><dd><span className="resource-badge">{categories[data.category]}</span></dd></div>
      <div><dt>Provider service</dt><dd>{data.providerService}</dd></div>
      <div><dt>Region</dt><dd>{data.region}</dd></div>
      <div><dt>Status</dt><dd>
        <span className={`resource-badge resource-status-${data.status.toLowerCase()}`}>{statuses[data.status]}</span>
      </dd></div>
      <div><dt>Created (UTC)</dt><dd><time dateTime={data.createdAt}>{formatTimestamp(data.createdAt)}</time></dd></div>
      <div><dt>Last updated (UTC)</dt><dd><time dateTime={data.updatedAt}>{formatTimestamp(data.updatedAt)}</time></dd></div>
    </dl>
  </>;
}
