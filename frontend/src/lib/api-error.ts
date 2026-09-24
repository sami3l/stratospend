export type ApiErrorResponse = {
  timestamp: string;
  status: number;
  message: string;
  errors: Record<string, string>;
};

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export class ApiError extends Error {
  readonly status: number;
  readonly timestamp?: ApiErrorResponse["timestamp"];
  readonly errors: ApiErrorResponse["errors"];

  constructor(status: number, body: unknown) {
    const problem = isObject(body) ? body : {};
    super(typeof problem.message === "string" && problem.message.trim()
      ? problem.message : `Request failed with status ${status}`);
    this.name = "ApiError";
    // The HTTP status remains authoritative if an invalid body reports a different status.
    this.status = status;
    this.timestamp = typeof problem.timestamp === "string" ? problem.timestamp : undefined;
    this.errors = isObject(problem.errors)
      ? Object.fromEntries(Object.entries(problem.errors)
        .filter((entry): entry is [string, string] => typeof entry[1] === "string"))
      : {};
  }
}
