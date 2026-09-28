"use client";

import { useRef, useState } from "react";
import { ApiError } from "@/lib/api-error";
import { createCloudResource, type ResourceCategory, type ResourceStatus } from "@/lib/cloud-resources";
import { validateResourceForm, type ResourceFormValues } from "@/lib/cloud-resource-form";

const emptyValues: ResourceFormValues = {
  cloudAccountId: "", externalResourceId: "", name: "", category: "", providerService: "", region: "", status: "",
};

export function useCreateCloudResource() {
  const [values, setValues] = useState(emptyValues);
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [feedback, setFeedback] = useState<{
    message: string; errors: Record<string, string>; status?: number; attempt: number;
  }>({ message: "", errors: {}, attempt: 0 });

  function changeField<K extends keyof ResourceFormValues>(field: K, value: ResourceFormValues[K]) {
    setValues((current) => ({ ...current, [field]: value }));
    setFeedback((current) => {
      const errors = { ...current.errors };
      delete errors[field];
      return { ...current, errors };
    });
  }

  async function submit() {
    // A ref also guards repeated submit events before React renders the disabled button.
    if (savingRef.current) return null;
    const errors = validateResourceForm(values);
    if (Object.keys(errors).length) {
      setFeedback((current) => ({ message: "Check the highlighted fields.", errors, attempt: current.attempt + 1 }));
      return null;
    }
    savingRef.current = true;
    setSaving(true);
    setFeedback((current) => ({ message: "", errors: {}, attempt: current.attempt }));
    try {
      return await createCloudResource({
        ...values, cloudAccountId: Number(values.cloudAccountId),
        category: values.category as ResourceCategory, status: values.status as ResourceStatus,
      });
    } catch (reason) {
      setFeedback((current) => ({
        message: reason instanceof Error ? reason.message : "Unable to add resource. Please try again.",
        errors: reason instanceof ApiError ? reason.errors : {},
        status: reason instanceof ApiError ? reason.status : undefined,
        attempt: current.attempt + 1,
      }));
      return null;
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  return { values, saving, feedback, changeField, submit };
}
