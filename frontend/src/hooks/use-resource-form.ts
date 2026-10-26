"use client";

import { useRef, useState } from "react";
import { ApiError } from "@/lib/api-error";

export type ResourceFormFeedback = {
  message: string; errors: Record<string, string>; status?: number; attempt: number;
};

type Options<V, R> = {
  initialValues: V;
  validate: (values: V) => Record<string, string>;
  save: (values: V) => Promise<R>;
  fallbackMessage: string;
};

export function useResourceForm<V extends object, R>({ initialValues, validate, save, fallbackMessage }: Options<V, R>) {
  const [values, setValues] = useState(initialValues);
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [feedback, setFeedback] = useState<ResourceFormFeedback>({ message: "", errors: {}, attempt: 0 });

  function changeField<K extends keyof V>(field: K, value: V[K]) {
    setValues((current) => ({ ...current, [field]: value }));
    setFeedback((current) => {
      const errors = { ...current.errors };
      delete errors[field as string];
      return { ...current, errors };
    });
  }

  async function submit() {
    // A ref also guards repeated submit events before React renders the disabled button.
    if (savingRef.current) return null;
    const errors = validate(values);
    if (Object.keys(errors).length) {
      setFeedback((current) => ({ message: "Check the highlighted fields.", errors, attempt: current.attempt + 1 }));
      return null;
    }
    savingRef.current = true;
    setSaving(true);
    setFeedback((current) => ({ message: "", errors: {}, attempt: current.attempt }));
    try {
      return await save(values);
    } catch (reason) {
      setFeedback((current) => ({
        message: reason instanceof Error ? reason.message : fallbackMessage,
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
