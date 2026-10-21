/**
 * General Land contracts; migration/extraction tooling belongs exclusively to zero.
 *
 * TODO(migration): Introduce capability-specific provider contracts for Region and village business
 * rules still owned by zero. zero registers adapters through the same contracts future independent
 * plugins will implement. Do not reference zero classes or legacy SQL here. Remove each zero adapter
 * after its replacement passes verification and the corresponding legacy entry point is disabled.
 *
 * TODO(migration): Expose stable claim-ID teleport targets and access checks to tokaPortal;
 * tokaSigns must use that same Portal execution path. Keep registration, cooldown, and transport
 * outside Land's domain model. Legacy target-name resolution and conversion stay in zero.
 */
package dev.toka.land.api;
