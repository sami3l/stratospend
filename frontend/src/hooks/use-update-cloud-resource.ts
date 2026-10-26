"use client";

import {
  updateCloudResource, type CloudResource, type ResourceCategory, type ResourceStatus,
} from "@/lib/cloud-resources";
import { validateResourceDetails, type ResourceDetailsValues } from "@/lib/cloud-resource-form";
import { useResourceForm } from "./use-resource-form";

const editableFields = ["name", "category", "providerService", "region", "status"] as const;

export function useUpdateCloudResource(resource: CloudResource) {
  const initialValues: ResourceDetailsValues = Object.fromEntries(
    editableFields.map((field) => [field, resource[field]])) as ResourceDetailsValues;

  return useResourceForm({
    initialValues, validate: validateResourceDetails,
    save: (values) => {
      // Unchanged values return the current resource without a request, so callers can tell nothing changed.
      if (editableFields.every((field) => values[field] === resource[field])) return Promise.resolve(resource);
      return updateCloudResource(resource.id, {
        ...values, category: values.category as ResourceCategory, status: values.status as ResourceStatus,
      });
    },
    fallbackMessage: "Unable to save resource. Please try again.",
  });
}
