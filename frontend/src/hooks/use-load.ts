"use client";

import { useEffect, useState } from "react";
import { ApiError } from "@/lib/api-error";

export type LoadState<T> = { status: "loading" }
  | { status: "success"; data: T }
  | { status: "error"; message: string; errors: Record<string, string>; httpStatus?: number };

export function useLoad<T>(load: () => Promise<T>, fallbackMessage: string) {
  const [revision, setRevision] = useState(0);
  const [result, setResult] = useState<{
    load: typeof load; revision: number; state: LoadState<T>;
  }>();

  useEffect(() => {
    let current = true;
    load().then(
      (data) => { if (current) setResult({ load, revision, state: { status: "success", data } }); },
      (reason: unknown) => {
        if (current) setResult({ load, revision, state: {
          status: "error", message: reason instanceof Error ? reason.message : fallbackMessage,
          errors: reason instanceof ApiError ? reason.errors : {},
          httpStatus: reason instanceof ApiError ? reason.status : undefined,
        } });
      },
    );
    // Ignore late successes and failures after a query change, retry or unmount.
    return () => { current = false; };
  }, [load, revision, fallbackMessage]);

  // An earlier query's rows and totals must not appear under the new filters.
  const state: LoadState<T> = result?.load === load && result.revision === revision
    ? result.state : { status: "loading" };
  return { ...state, retry: () => setRevision((value) => value + 1) };
}
