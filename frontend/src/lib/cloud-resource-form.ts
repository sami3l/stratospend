import type { CloudResourceCreateInput, ResourceCategory, ResourceStatus } from "./cloud-resources";

export const resourceCategories: Record<ResourceCategory, string> = {
  COMPUTE: "Compute", STORAGE: "Storage", DATABASE: "Database", NETWORK: "Network",
  CONTAINER: "Container", SERVERLESS: "Serverless", OTHER: "Other",
};
export const resourceStatuses: Record<ResourceStatus, string> = {
  ACTIVE: "Active", INACTIVE: "Inactive", UNKNOWN: "Unknown",
};

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

export function validateResourceForm(values: ResourceFormValues): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!Number.isSafeInteger(Number(values.cloudAccountId)) || Number(values.cloudAccountId) <= 0) {
    errors.cloudAccountId = "Select a cloud account with a positive ID.";
  }
  for (const [field, { label, maxLength }] of Object.entries(resourceStringFields)) {
    const value = values[field as keyof typeof resourceStringFields];
    // Check blankness without changing the submitted value, particularly provider identifiers.
    if (!value.trim()) errors[field] = `${label} is required.`;
    else if (value.length > maxLength) errors[field] = `${label} must be ${maxLength} characters or fewer.`;
  }
  if (!Object.hasOwn(resourceCategories, values.category)) errors.category = "Select a category.";
  if (!Object.hasOwn(resourceStatuses, values.status)) errors.status = "Select a status.";
  if (!errors.region && !/^[a-z0-9-]+$/.test(values.region)) {
    errors.region = "Use only lowercase letters, digits and hyphens, or global.";
  }
  return errors;
}
