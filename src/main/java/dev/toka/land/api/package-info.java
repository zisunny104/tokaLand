/**
 * General Land contracts; migration/extraction tooling belongs exclusively to zero.
 *
 * TODO(migration): Introduce capability-specific provider contracts for Region and village business
 * rules still owned by zero. zero registers adapters through the same contracts future independent
 * plugins will implement. Do not reference zero classes or legacy SQL here. Remove each zero adapter
 * after its replacement passes verification and the corresponding legacy entry point is disabled.
 *
 * tokaPortal reads owner-only, stable-ID teleport targets through Main.queryTeleportTargetsJson
 * and Main.queryTeleportTargetJson. It remains responsible for events, cooldown, and transport;
 * tokaSigns should call that same Portal execution path. Legacy conversion stays in zero.
 */
package dev.toka.land.api;
