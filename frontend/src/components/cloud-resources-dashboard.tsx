"use client";

import { useCloudResourceInventory } from "@/hooks/use-cloud-resource-inventory";
import type { CloudProvider } from "@/lib/cloud-accounts";
import type { CloudResourceQuery, ResourceCategory, ResourceStatus } from "@/lib/cloud-resources";

const providers: Record<CloudProvider, string> = { AWS: "AWS", AZURE: "Azure", GCP: "Google Cloud" };
const categories: Record<ResourceCategory, string> = {
  COMPUTE: "Compute", STORAGE: "Storage", DATABASE: "Database", NETWORK: "Network",
  CONTAINER: "Container", SERVERLESS: "Serverless", OTHER: "Other",
};
const statuses: Record<ResourceStatus, string> = { ACTIVE: "Active", INACTIVE: "Inactive", UNKNOWN: "Unknown" };
const sorts = [
  ["createdAt,desc", "Newest created"], ["createdAt,asc", "Oldest created"],
  ["updatedAt,desc", "Recently updated"], ["updatedAt,asc", "Least recently updated"],
  ["name,asc", "Name: A to Z"], ["name,desc", "Name: Z to A"],
  ["category,asc", "Category: A to Z"], ["category,desc", "Category: Z to A"],
  ["providerService,asc", "Service: A to Z"], ["providerService,desc", "Service: Z to A"],
  ["region,asc", "Region: A to Z"], ["region,desc", "Region: Z to A"],
  ["status,asc", "Status: A to Z"], ["status,desc", "Status: Z to A"],
  ["id,asc", "ID: ascending"], ["id,desc", "ID: descending"],
] satisfies [NonNullable<CloudResourceQuery["sort"]>, string][];

function timestamp(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unavailable" : date.toISOString().replace("T", " ").replace(/\.\d+Z$/, " UTC");
}

export function CloudResourcesDashboard() {
  const { query, resources, accounts, changeFilters, resetFilters, changePage, hasFilters } = useCloudResourceInventory();
  const page = resources.status === "success" ? resources.data : undefined;

  return <>
    <header className="page-heading">
      <div><p className="eyebrow">CLOUD INVENTORY</p><h1>Cloud resources</h1></div>
      <p>Explore resources across your cloud accounts. Filter by account, provider, category, region or status.</p>
    </header>
    <p className="resource-total" aria-live="polite">
      {page ? <><strong>{page.totalElements}</strong> resources matching current filters</>
        : resources.status === "error" ? "Resource total unavailable" : "Loading resource total…"}
    </p>

    <section className="resource-filters" aria-label="Resource filters">
      <label>Cloud account
        <select value={query.cloudAccountId ?? ""} disabled={accounts.status !== "success"}
          aria-describedby={accounts.status !== "success" || accounts.data.length === 0 ? "account-filter-status" : undefined}
          onChange={(event) => changeFilters({ cloudAccountId: event.target.value ? Number(event.target.value) : undefined })}>
          <option value="">All accounts</option>
          {accounts.status === "success" && accounts.data.map((account) =>
            <option key={account.id} value={account.id}>{account.name}</option>)}
        </select>
      </label>
      <label>Provider
        <select value={query.provider ?? ""} onChange={(event) => changeFilters({ provider: event.target.value as CloudProvider || undefined })}>
          <option value="">All providers</option>
          {Object.entries(providers).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label>Category
        <select value={query.category ?? ""} onChange={(event) => changeFilters({ category: event.target.value as ResourceCategory || undefined })}>
          <option value="">All categories</option>
          {Object.entries(categories).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label>Region
        <input value={query.region ?? ""} maxLength={40} placeholder="e.g. eu-west-1 or global"
          onChange={(event) => changeFilters({ region: event.target.value || undefined })}/>
      </label>
      <label>Status
        <select value={query.status ?? ""} onChange={(event) => changeFilters({ status: event.target.value as ResourceStatus || undefined })}>
          <option value="">All statuses</option>
          {Object.entries(statuses).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label>Sort by
        <select value={query.sort} onChange={(event) => changeFilters({ sort: event.target.value as CloudResourceQuery["sort"] })}>
          {sorts.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <button className="inventory-button" onClick={resetFilters}>Reset filters</button>
    </section>

    {accounts.status === "loading" && <p id="account-filter-status" role="status">Loading cloud accounts…</p>}
    {accounts.status === "error" && <div className="inventory-alert" role="alert" id="account-filter-status">
      <p>Cloud account filter unavailable: {accounts.message}</p>
      <button className="inventory-button" onClick={accounts.retry}>Retry cloud accounts</button>
    </div>}
    {accounts.status === "success" && accounts.data.length === 0 &&
      <p id="account-filter-status">No cloud accounts available to filter.</p>}

    <section aria-label="Resource results" aria-busy={resources.status === "loading"}>
      {resources.status === "loading" && <div className="state" role="status">Loading cloud resources…</div>}
      {resources.status === "error" && <div className="error" role="alert">
        <p>{resources.message}</p>
        {Object.keys(resources.errors).length > 0 && <ul className="resource-field-errors">
          {Object.entries(resources.errors).map(([field, message]) => <li key={field}>{field}: {message}</li>)}
        </ul>}
        <button className="inventory-button" onClick={resources.retry}>Retry resources</button>
      </div>}
      {page && (page.content.length === 0 ? <div className="state">
        <strong>{page.totalElements > 0 ? "No resources on this page" : hasFilters ? "No matching resources" : "No cloud resources yet"}</strong>
        <p>{page.totalElements > 0 ? "Use Previous to return to an earlier page."
          : hasFilters ? "Try adjusting or resetting your filters." : "Resources will appear here when they are added to your cloud accounts."}</p>
      </div> : <div className="resource-table-scroll" role="region" aria-label="Cloud resource inventory table" tabIndex={0}>
        <table className="resource-table">
          <caption className="visually-hidden">Cloud resources matching the current filters</caption>
          <thead><tr>{["Resource", "Account", "Provider", "Category", "Provider service", "Region", "Status", "Updated (UTC)"].map((heading) =>
            <th scope="col" key={heading}>{heading}</th>)}</tr></thead>
          <tbody>{page.content.map((resource) => <tr key={resource.id}>
            <th scope="row"><strong>{resource.name}</strong><code>{resource.externalResourceId}</code></th>
            <td>{resource.cloudAccountName}</td>
            <td><span className={`provider ${resource.provider.toLowerCase()}`}>{providers[resource.provider]}</span></td>
            <td><span className="resource-badge">{categories[resource.category]}</span></td>
            <td>{resource.providerService}</td><td>{resource.region}</td>
            <td><span className={`resource-badge resource-status-${resource.status.toLowerCase()}`}>{statuses[resource.status]}</span></td>
            <td><time dateTime={resource.updatedAt}>{timestamp(resource.updatedAt)}</time></td>
          </tr>)}</tbody>
        </table>
      </div>)}
    </section>
    <nav className="resource-pagination" aria-label="Resource pagination">
      <button className="inventory-button" disabled={!page || page.page <= 0}
        onClick={() => page && changePage(page.page - 1)}>Previous</button>
      <span aria-live="polite">{page ? page.totalPages === 0 ? "No pages" : `Page ${page.page + 1} of ${page.totalPages}` : "Page unavailable"}</span>
      <button className="inventory-button" disabled={!page || page.page + 1 >= page.totalPages}
        onClick={() => page && changePage(page.page + 1)}>Next</button>
    </nav>
  </>;
}
