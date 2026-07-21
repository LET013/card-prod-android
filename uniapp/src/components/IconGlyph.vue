<template>
  <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-if="name==='settings'">
      <circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6V21h-4v-.1a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H3v-4h.1a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-1.6V3h4v.1a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.1v4H21a1.7 1.7 0 0 0-1.6 1z"/>
    </template>
    <template v-else-if="name==='user' || name==='people'">
      <circle cx="9" cy="8" r="3"/><path d="M3.5 20c.4-4 2.2-6 5.5-6s5.1 2 5.5 6"/><circle v-if="name==='people'" cx="17" cy="9" r="2"/><path v-if="name==='people'" d="M15.2 15.5c3.2-.3 5 1.2 5.3 4.5"/>
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
    <template v-else-if="name==='fingerprint'">
      <path d="M8 11a4 4 0 0 1 8 0c0 5-1 7-2 9M6 12a6 6 0 0 1 12 0c0 3-.4 6-1.5 8M10 12a2 2 0 0 1 4 0c0 4-.6 6-1.5 8M4 12a8 8 0 0 1 16 0M8.5 18c.7-1.7 1-3.5 1-6"/>
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
    <template v-else-if="name==='engine'">
      <circle cx="10" cy="12" r="6"/><path d="M10 8v4l3 2M17 7l1-2M18 12h3M17 17l1 2"/>
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
    <template v-else>
      <circle cx="12" cy="12" r="8"/><path d="M9 12h6M12 9v6"/>
    </template>
  </svg>
</template>
<script setup>
defineProps({ name: { type:String, default:'settings' } })
</script>
<style scoped>.icon{width:100%;height:100%;display:block;}</style>
