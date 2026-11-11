# Frontend redesign reference review (2026-11-11)

Read installed frontend-design and impeccable product guidance. taste-skill explicitly excludes dashboards/data tables, so it is not used as an implementation prescription; no migration to React or decorative marketing effects.

- [Chatwoot](https://github.com/chatwoot/chatwoot), [dashboard guide](https://app.chatwoot.com/hc/user-guide/articles/1677231493-lesson-2-dashboard-basics): inbox/list/thread/context separation, contextual controls. LICENSE outside enterprise is MIT Expat; enterprise has separate terms. Reference workflow and region hierarchy only.
- [FreeScout](https://github.com/freescout-help-desk/freescout): mailbox-like work queues, concise status and assignment. LICENSE is AGPL-3.0. No source/styles/assets copied.
- [Papercups](https://github.com/papercups-io/papercups): focused conversation layout and compact utility navigation. LICENSE MIT. No source/styles/assets copied.

Plan: service inbox, not a dashboard template. A narrow charcoal tool rail, light queue navigation, a persistent list and a case workspace; service-ticket identity uses a restrained repair-label orange, white surfaces, readable Chinese sans and tabular numbers. Primary task is handling the next customer problem. Remove slogan hero, giant empty panel, broad dark sidebar, badge-heavy table, permanently visible AI infrastructure copy. Preserve permission-driven actions, business history, AI mode honesty and explicit confirmations.

Tokens: ink #252724, surface #FFFFFF, navigation #F5F6F3, border #E2E5DE, service #B5482B, secondary #64716B. These are engineering design choices, not attributed to the user or a borrowed official design system. Native layout plus existing Element Plus interactions.

Validate with existing 12 business/UI tests, a navigation/keyboard/mobile regression, real role screenshots, and actual container rebuild. No synthetic metrics, no invented channels or fake response times.
