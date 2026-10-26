import type { CloudProvider } from "./cloud-accounts";
import type { CloudResourceCreateInput, ResourceCategory, ResourceStatus } from "./cloud-resources";

export const providerLabels: Record<CloudProvider, string> = { AWS: "AWS", AZURE: "Azure", GCP: "Google Cloud" };
export const resourceCategories: Record<ResourceCategory, string> = {
  COMPUTE: "Compute", STORAGE: "Storage", DATABASE: "Database", NETWORK: "Network",
  CONTAINER: "Container", SERVERLESS: "Serverless", OTHER: "Other",
};
export const resourceStatuses: Record<ResourceStatus, string> = {
  ACTIVE: "Active", INACTIVE: "Inactive", UNKNOWN: "Unknown",
};

export function formatTimestamp(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unavailable" : date.toISOString().replace("T", " ").replace(/\.\d+Z$/, " UTC");
}

export const resourceStringFields = {
  externalResourceId: { label: "External resource ID", maxLength: 512 },
  name: { label: "Name", maxLength: 120 },
  providerService: { label: "Provider service", maxLength: 80 },
  region: { label: "Region", maxLength: 40 },
} as const;

export type ResourceFormValues = Omit<CloudResourceCreateInput, "cloudAccountId" | "category" | "status"> & {
  cloudAccountId: string;
  category: ResourceCategory | "";
  status: ResourceStatus | "";
};

// The fields that remain editable after registration, matching the backend update contract.
export type ResourceDetailsValues = Omit<ResourceFormValues, "cloudAccountId" | "externalResourceId">;

function validateString(errors: Record<string, string>, field: keyof typeof resourceStringFields, value: string) {
  const { label, maxLength } = resourceStringFields[field];
  // Check blankness without changing the submitted value, particularly provider identifiers.
  if (!value.trim()) errors[field] = `${label} is required.`;
  else if (value.length > maxLength) errors[field] = `${label} must be ${maxLength} characters or fewer.`;
}

export function validateResourceDetails(values: ResourceDetailsValues): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const field of ["name", "providerService", "region"] as const) validateString(errors, field, values[field]);
  if (!Object.hasOwn(resourceCategories, values.category)) errors.category = "Select a category.";
  if (!Object.hasOwn(resourceStatuses, values.status)) errors.status = "Select a status.";
  if (!errors.region && !/^[a-z0-9-]+$/.test(values.region)) {
    errors.region = "Use only lowercase letters, digits and hyphens, or global.";
  }
  return errors;
}

export function validateResourceForm(values: ResourceFormValues): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!Number.isSafeInteger(Number(values.cloudAccountId)) || Number(values.cloudAccountId) <= 0) {
    errors.cloudAccountId = "Select a cloud account with a positive ID.";
  }
  validateString(errors, "externalResourceId", values.externalResourceId);
  return { ...errors, ...validateResourceDetails(values) };
}
