<template>
  <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-if="name==='settings'">
      <circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6V21h-4v-.1a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H3v-4h.1a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-1.6V3h4v.1a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.1v4H21a1.7 1.7 0 0 0-1.6 1z"/>
    </template>
    <template v-else-if="name==='user'">
      <circle cx="12" cy="8" r="3"/><path d="M6.5 20c.4-4 2.2-6 5.5-6s5.1 2 5.5 6"/>
    </template>
    <template v-else-if="name==='search'">
      <circle cx="11" cy="11" r="6"/><path d="M16 16l4 4"/>
    </template>
    <template v-else-if="name==='people'">
      <circle cx="9" cy="8" r="3"/><path d="M3.5 20c.4-4 2.2-6 5.5-6s5.1 2 5.5 6"/><circle cx="17" cy="9" r="2"/><path d="M15.2 15.5c3.2-.3 5 1.2 5.3 4.5"/>
    </template>
    <template v-else-if="name==='role-manage'">
      <circle cx="6" cy="12" r="2.5"/><circle cx="17" cy="6" r="2.5"/><circle cx="17" cy="18" r="2.5"/><path d="M8.2 10.8l6.6-3.6M8.2 13.2l6.6 3.6"/>
    </template>
    <template v-else-if="name==='user-manage'">
      <circle cx="9" cy="7.5" r="3"/><path d="M3.8 19c.4-4 2.2-6 5.2-6 1.8 0 3.2.7 4.1 2"/><circle cx="17.5" cy="16.5" r="2.2"/><circle cx="17.5" cy="16.5" r=".55" fill="currentColor" stroke="none"/><path d="M17.5 12.6v1.1M17.5 19.3v1.1M13.6 16.5h1.1M20.3 16.5h1.1M14.7 13.7l.8.8M19.5 18.5l.8.8M20.3 13.7l-.8.8M15.5 18.5l-.8.8"/>
    </template>
    <template v-else-if="name==='password-change'">
      <rect x="5" y="10" width="14" height="10" rx="2.5"/><path d="M8.5 10V7.8a3.5 3.5 0 0 1 6.7-1.4M12 14v2.5"/>
    </template>
    <template v-else-if="name==='employee-manage'">
      <circle cx="8.5" cy="8" r="2.7"/><path d="M3.5 19c.5-3.7 2.2-5.5 5-5.5s4.5 1.8 5 5.5"/><circle cx="16.5" cy="9" r="2.1"/><path d="M14.2 14.2c.8-.5 1.7-.7 2.7-.7 2.3 0 3.8 1.5 4.1 4.5"/>
    </template>
    <template v-else-if="name==='unit-manage'">
      <rect x="4" y="3" width="13" height="18" rx="2.2"/><path d="M8 8h5M8 11.5h4"/><rect x="14" y="14" width="6" height="5" rx="1.3"/><path d="M15.5 14v-1.2a1.5 1.5 0 0 1 3 0V14M17 16.2v1"/>
    </template>
    <template v-else-if="name==='history-manage'">
      <g stroke-width="2" transform="translate(12 12) scale(1.07 1.192) translate(-12 -12)">
        <path d="M15 19H7.5A3.5 3.5 0 0 1 4 15.5v-8A3.5 3.5 0 0 1 7.5 4H14"/>
        <path d="M8 8h5M8 12h4"/>
        <path d="M15 7c3 1 5 3.8 5 7v4M17 15.5l3 3 3-3"/>
      </g>
    </template>
    <template v-else-if="name==='list'">
      <rect x="4" y="5" width="16" height="14" rx="2"/><path d="M8 9h8M8 13h8M8 17h5"/><circle cx="6" cy="9" r=".4" fill="currentColor"/><circle cx="6" cy="13" r=".4" fill="currentColor"/><circle cx="6" cy="17" r=".4" fill="currentColor"/>
    </template>
    <template v-else-if="name==='lock'">
      <rect x="5" y="10" width="14" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/>
    </template>
    <template v-else-if="name==='refresh'">
      <path d="M20 6v5h-5M4 18v-5h5"/><path d="M6.1 8a7 7 0 0 1 11.6-1.6L20 11M4 13l2.3 4.6A7 7 0 0 0 18 16"/>
    </template>
    <template v-else-if="name==='shield'">
      <path d="M12 3l7 3v5c0 4.4-2.6 7.8-7 10-4.4-2.2-7-5.6-7-10V6l7-3z"/><path d="M9 12l2 2 4-4"/>
    </template>
    <template v-else-if="name==='tool'">
      <path d="M14.5 6.5a4 4 0 0 0-5 5L3 18l3 3 6.5-6.5a4 4 0 0 0 5-5l-2 2-3-3 2-2z"/>
    </template>
    <template v-else-if="name==='face'">
      <path d="M7 3H4a1 1 0 0 0-1 1v3M17 3h3a1 1 0 0 1 1 1v3M7 21H4a1 1 0 0 1-1-1v-3M17 21h3a1 1 0 0 0 1-1v-3"/><circle cx="12" cy="11" r="4"/><path d="M10 11h.01M14 11h.01M10.5 13.5c1 .7 2 .7 3 0"/>
    </template>
    <template v-else-if="name==='face-register'">
      <path d="M8 4H5a1 1 0 0 0-1 1v3M16 4h3a1 1 0 0 1 1 1v3M8 20H5a1 1 0 0 1-1-1v-3M16 20h3a1 1 0 0 0 1-1v-3"/><circle cx="12" cy="12" r="3.6"/><path d="M10.5 11.5h.01M13.5 11.5h.01M10.5 13.6c1 .7 2 .7 3 0"/>
    </template>
    <template v-else-if="name==='fingerprint' || name==='finger'">
      <path d="M7.2 9.4a5.2 5.2 0 0 1 9.6 0"/>
      <path d="M5.2 12a6.8 6.8 0 0 1 13.6 0"/>
      <path d="M8.9 12.2a3.1 3.1 0 0 1 6.2 0"/>
      <path d="M11 12.4a1.1 1.1 0 0 1 2.2 0c0 3.8-.4 6.2-1.4 8.1"/>
      <path d="M15 14c-.1 2.4-.5 4.4-1.3 6"/>
      <path d="M8.8 15.2c-.3 1.7-.8 3.2-1.6 4.6"/>
      <path d="M5.3 15.4c-.2 1-.6 2-1.1 2.8"/>
      <path d="M18.7 15c-.1 1.2-.3 2.4-.7 3.5"/>
      <path d="M9 4.7a8.2 8.2 0 0 1 9.2 2.1"/>
      <path d="M5.8 6.8A8.2 8.2 0 0 1 7 5.8"/>
    </template>
    <template v-else-if="name==='history'">
      <path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5M12 7v5l3 2"/>
    </template>
    <template v-else-if="name==='sliders'">
      <path d="M4 6h16M4 12h16M4 18h16"/><circle cx="9" cy="6" r="2" fill="white"/><circle cx="15" cy="12" r="2" fill="white"/><circle cx="11" cy="18" r="2" fill="white"/>
    </template>
    <template v-else-if="name==='device'">
      <rect x="5" y="3" width="14" height="18" rx="2"/><path d="M9 7h6M9 11h6M9 15h3"/><circle cx="16.5" cy="17.5" r="2.5"/>
    </template>
    <template v-else-if="name==='device-settings'">
      <rect x="4" y="3.5" width="12" height="17" rx="2.2"/><path d="M7.5 8h5M7.5 11.5h4"/><circle cx="17" cy="16.5" r="2.2"/><circle cx="17" cy="16.5" r=".55" fill="currentColor" stroke="none"/><path d="M17 12.7v1M17 19.3v1M13.2 16.5h1M19.8 16.5h1M14.3 13.8l.7.7M19 18.5l.7.7M19.7 13.8l-.7.7M15 18.5l-.7.7"/>
    </template>
    <template v-else-if="name==='network'">
      <path d="M5 8.5a11 11 0 0 1 14 0"/><path d="M8 12a6.5 6.5 0 0 1 8 0"/><path d="M10.8 15.2a2 2 0 0 1 2.4 0"/><circle cx="12" cy="18" r="1"/>
    </template>
    <template v-else-if="name==='camera'">
      <rect x="4" y="7" width="16" height="12" rx="2"/><path d="M8 7l1.5-2h5L16 7"/><circle cx="12" cy="13" r="3"/><path d="M18 10h.01"/>
    </template>
    <template v-else-if="name==='engine'">
      <circle cx="10" cy="12" r="6"/><path d="M10 8v4l3 2M17 7l1-2M18 12h3M17 17l1 2"/>
    </template>
    <template v-else-if="name==='restart-app'">
      <path stroke-width="2" transform="translate(12 12) scale(.822) translate(-12 -12)" d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1"/>
    </template>
    <template v-else-if="name==='authorization'">
      <path d="M12 3l2.1 1.8 2.8-.2.6 2.7 2.3 1.5-1.1 2.6 1.1 2.6-2.3 1.5-.6 2.7-2.8-.2L12 21l-2.1-1.8-2.8.2-.6-2.7-2.3-1.5 1.1-2.6-1.1-2.6 2.3-1.5.6-2.7 2.8.2z"/><path d="M12 8.2l1.1 2.2 2.4.4-1.8 1.7.4 2.4-2.1-1.1-2.1 1.1.4-2.4-1.8-1.7 2.4-.4z"/>
    </template>
    <template v-else-if="name==='board-upgrade'">
      <g stroke-width="2" transform="translate(12 12) scale(1.074 .967) translate(-12 -12)">
        <path d="M9 5H7.5A3.5 3.5 0 0 0 4 8.5v8A3.5 3.5 0 0 0 7.5 20h9a3.5 3.5 0 0 0 3.5-3.5v-8A3.5 3.5 0 0 0 16.5 5H15V4h-2v1h-2V4H9z"/>
        <path d="M12 8v7M9 12l3 3 3-3"/>
      </g>
    </template>
    <template v-else-if="name==='app-upgrade'">
      <g stroke-width="2" transform="translate(12 12) scale(1.102 1.053) translate(-12 -12)">
        <path d="M15.5 19.5h-8A3.5 3.5 0 0 1 4 16V7.5A3.5 3.5 0 0 1 7.5 4H14a3 3 0 0 1 3 3v6"/>
        <path d="M10.5 8v6M8 11.5l2.5 2.5 2.5-2.5"/>
        <circle cx="18.5" cy="17.5" r="2.5"/>
      </g>
    </template>
    <template v-else-if="name==='eject'">
      <g stroke-width="2" transform="translate(12 12) scale(1.074 1.079) translate(-12 -12)">
        <rect x="4" y="5" width="16" height="5.5" rx="2"/>
        <rect x="4" y="13.5" width="16" height="5.5" rx="2"/>
        <path d="M7.5 16.25h3M15.5 16.25h1"/>
      </g>
    </template>
    <template v-else-if="name==='hardware'">
      <g stroke-width="2" transform="translate(12 12) scale(1.225 1.012) translate(-12 -12)">
        <rect x="4.5" y="3.5" width="15" height="17" rx="3.5"/>
        <rect x="13" y="6" width="4.5" height="2.5" rx="1.25"/>
        <path d="M8 8h2.5M8 12h8M8 16h6"/>
      </g>
    </template>
    <template v-else-if="name==='work-card-upgrade'">
      <g stroke-width="2" transform="translate(12 12) scale(1.06 1.094) translate(-12 -12)">
        <path d="M16 19.5H7.5A3.5 3.5 0 0 1 4 16V7.5A3.5 3.5 0 0 1 7.5 4H15a3 3 0 0 1 3 3v1"/>
        <rect x="18.5" y="7.5" width="2.5" height="8" rx="1.25"/>
        <path d="M11 8v6M8.5 11.5L11 14l2.5-2.5M8 15.5v1A1.5 1.5 0 0 0 9.5 18h4a1.5 1.5 0 0 0 1.5-1.5v-1"/>
      </g>
    </template>
    <template v-else-if="name==='command-check'">
      <g stroke-width="2" transform="translate(12 12) scale(1.08 1.065) translate(-12 -12)">
        <path d="M15.5 20h-8A3.5 3.5 0 0 1 4 16.5v-9A3.5 3.5 0 0 1 7.5 4H15a3 3 0 0 1 3 3v5"/>
        <path d="M7.2 11a3.8 3.8 0 0 1 7.6 0M8.6 12.5a2.4 2.4 0 0 1 4.8 0c0 2.3-.3 3.8-1 5M10.2 13a.9.9 0 0 1 1.8 0c0 1.8-.2 3-.7 4.2M7.4 14c-.2 1.2-.5 2.2-1 3"/>
        <circle cx="18.5" cy="17.5" r="2.5"/>
      </g>
    </template>
    <template v-else-if="name==='main-board'">
      <g stroke-width="2" transform="translate(12 12) scale(1 1.112) translate(-12 -12)">
        <rect x="5.5" y="4.5" width="13" height="15" rx="3.5"/>
        <path d="M2.5 8h3M2.5 12h3M2.5 16h3M18.5 8h3M18.5 12h3M18.5 16h3"/>
        <path d="M12 8v6M9.5 11.5L12 14l2.5-2.5M8.5 15.5v.5a2 2 0 0 0 2 2h3a2 2 0 0 0 2-2v-.5"/>
      </g>
    </template>
    <template v-else-if="name==='upgrade'">
      <path d="M12 16V4M8 8l4-4 4 4"/><rect x="4" y="16" width="16" height="5" rx="1"/>
    </template>
    <template v-else-if="name==='card'">
      <rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 9h18M7 14h4"/>
    </template>
    <template v-else-if="name==='trash'">
      <path d="M4 7h16M10 3h4M7 7l1 14h8l1-14M10 11v6M14 11v6"/>
    </template>
    <template v-else-if="name==='badge'">
      <rect x="4" y="5" width="16" height="14" rx="2"/><circle cx="9" cy="11" r="2"/><path d="M6.5 16c.3-1.8 1.1-2.8 2.5-2.8s2.2 1 2.5 2.8M14 10h3M14 14h3"/>
    </template>
    <template v-else-if="name==='grid'">
      <rect x="4" y="4" width="6" height="6" rx="1"/><rect x="14" y="4" width="6" height="6" rx="1"/><rect x="4" y="14" width="6" height="6" rx="1"/><rect x="14" y="14" width="6" height="6" rx="1"/>
    </template>
    <template v-else-if="name==='cabinet-status'">
      <rect x="4" y="4" width="16" height="7" rx="2"/><rect x="4" y="13" width="16" height="7" rx="2"/><path d="M8 7.5h5M8 16.5h5"/><circle cx="17" cy="7.5" r="1" fill="currentColor"/><circle cx="17" cy="16.5" r="1" fill="currentColor"/>
    </template>
    <template v-else-if="name==='tab-status'">
      <rect x="4" y="5" width="16" height="5" rx="1.7"/><rect x="4" y="14" width="16" height="5" rx="1.7"/><path d="M8 7.5h7M8 16.5h7"/>
    </template>
    <template v-else-if="name==='card-slot-status'">
      <g stroke-width="2" transform="translate(12 12) scale(1.16 .921) translate(-12 -12)">
        <path d="M13.5 4H7a3 3 0 0 0-3 3v10a3 3 0 0 0 3 3h8a3 3 0 0 0 3-3v-1"/>
        <path d="M7 9h4M7 13h5"/>
        <path d="M20 7.5c0 3.4-4 6.8-4 6.8s-4-3.4-4-6.8a4 4 0 1 1 8 0z"/>
        <circle cx="16" cy="7.5" r="1.35"/>
      </g>
    </template>
    <template v-else-if="name==='mqtt-status'">
      <g stroke-width="2" transform="translate(12 12) scale(.938 1.06) translate(-12 -12)">
        <path d="M14.5 18H10l-4 3v-3A3.5 3.5 0 0 1 3 14.5v-7A3.5 3.5 0 0 1 6.5 4H14M18 11v3a4 4 0 0 1-3.5 4"/>
        <circle cx="14.5" cy="10" r="1" fill="currentColor" stroke="none"/>
        <path d="M17 7.5a3.5 3.5 0 0 1 3.5 3.5M17 4a7 7 0 0 1 7 7"/>
      </g>
    </template>
    <template v-else-if="name==='signal'">
      <path d="M5 19v-3M10 19v-6M15 19v-10M20 19V5"/>
    </template>
    <template v-else-if="name==='logout'">
      <path d="M10 5H5v14h5M14 8l4 4-4 4M8 12h10"/>
    </template>
    <template v-else-if="name==='chevron-right'">
      <path d="M9 5l7 7-7 7"/>
    </template>
    <template v-else>
      <circle cx="12" cy="12" r="8"/><path d="M9 12h6M12 9v6"/>
    </template>
  </svg>
</template>
<script setup>
defineProps({ name: { type:String, default:'settings' } })
</script>
<style scoped>.icon{width:100%;height:100%;display:block;}</style>
