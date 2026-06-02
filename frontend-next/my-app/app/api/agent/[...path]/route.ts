import { getServerSession } from "next-auth";
import { authOptions } from "@/app/api/auth/[...nextauth]/route";

export const runtime = "nodejs";

type RouteContext = {
  params: Promise<{ path: string[] }>;
};

const FORWARDED_RESPONSE_HEADERS = [
  "content-type",
  "cache-control",
  "x-request-id",
  "retry-after",
  "x-ratelimit-limit",
  "x-ratelimit-remaining",
] as const;

async function proxy(request: Request, context: RouteContext): Promise<Response> {
  const { path } = await context.params;
  const forwardedPath = path[0] === "api" ? path.slice(1) : path;
  const relativePath = `/${forwardedPath.join("/")}`;
  const upstreamBase = process.env.AGENT_BACKEND_URL ?? "http://localhost:8080";
  const upstreamUrl = new URL(`/api/${forwardedPath.join("/")}`, upstreamBase);
  upstreamUrl.search = new URL(request.url).search;

  const session = await getServerSession(authOptions);
  const user = session?.user as { id?: string; name?: string | null; email?: string | null } | undefined;

  const headers = new Headers();
  const contentType = request.headers.get("content-type");
  const accept = request.headers.get("accept");
  const requestId = request.headers.get("x-request-id");
  if (contentType) {
    headers.set("content-type", contentType);
  }
  if (accept) {
    headers.set("accept", accept);
  }
  if (requestId) {
    headers.set("x-request-id", requestId);
  }

  const internalSecret = process.env.INTERNAL_API_SECRET ?? "";
  if (isSensitivePath(relativePath) && !internalSecret) {
    return Response.json(
      { error: "Backend internal secret is not configured." },
      { status: 500 }
    );
  }
  if (internalSecret) {
    headers.set("x-internal-api-key", internalSecret);
  }

  if (user?.id) {
    headers.set("x-session-user-id", user.id);
  }
  if (user?.name) {
    headers.set("x-session-user-name", user.name);
  }
  if (user?.email) {
    headers.set("x-session-user-email", user.email);
  }

  const method = request.method.toUpperCase();
  const body =
    method === "GET" || method === "HEAD" || method === "OPTIONS"
      ? undefined
      : await request.arrayBuffer();

  const upstreamResponse = await fetch(upstreamUrl.toString(), {
    method,
    headers,
    body,
    cache: "no-store",
  });

  const responseHeaders = new Headers();
  for (const headerName of FORWARDED_RESPONSE_HEADERS) {
    const value = upstreamResponse.headers.get(headerName);
    if (value) {
      responseHeaders.set(headerName, value);
    }
  }

  return new Response(upstreamResponse.body, {
    status: upstreamResponse.status,
    headers: responseHeaders,
  });
}

export async function GET(request: Request, context: RouteContext): Promise<Response> {
  return proxy(request, context);
}

export async function POST(request: Request, context: RouteContext): Promise<Response> {
  return proxy(request, context);
}

export async function PATCH(request: Request, context: RouteContext): Promise<Response> {
  return proxy(request, context);
}

export async function DELETE(request: Request, context: RouteContext): Promise<Response> {
  return proxy(request, context);
}

export async function OPTIONS(request: Request, context: RouteContext): Promise<Response> {
  return proxy(request, context);
}

function isSensitivePath(path: string): boolean {
  return (
    path.startsWith("/chat") ||
    path.startsWith("/conversations") ||
    path.startsWith("/feedback")
  );
}
