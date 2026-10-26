"use client";

import { createCloudResource, type ResourceCategory, type ResourceStatus } from "@/lib/cloud-resources";
import { validateResourceForm, type ResourceFormValues } from "@/lib/cloud-resource-form";
import { useResourceForm } from "./use-resource-form";

const emptyValues: ResourceFormValues = {
  cloudAccountId: "", externalResourceId: "", name: "", category: "", providerService: "", region: "", status: "",
};

function save(values: ResourceFormValues) {
  return createCloudResource({
    ...values, cloudAccountId: Number(values.cloudAccountId),
    category: values.category as ResourceCategory, status: values.status as ResourceStatus,
  });
}

export function useCreateCloudResource() {
  return useResourceForm({
    initialValues: emptyValues, validate: validateResourceForm, save,
    fallbackMessage: "Unable to add resource. Please try again.",
  });
}
