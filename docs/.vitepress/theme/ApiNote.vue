<script setup lang="ts">
import { withBase } from 'vitepress'

// Tells the reader of a page that shows only the core API which API it is, and
// where the client API page that does the same is. It goes right below the
// title of a page that shows only the core API.
defineProps<{
  // The client API page or section that covers the same task.
  client?: string
  // Its title, as the link text.
  title?: string
}>()
</script>

<template>
  <div class="info custom-block">
    <p class="custom-block-title">Core API</p>
    <p>
      This page uses the core API in <code>dev.onvoid.webrtc</code>, which leaves negotiation, threads and
      cleanup to you.
      <template v-if="client">For the client API, see <a :href="withBase(client)">{{ title }}</a>.</template>
    </p>
  </div>
</template>
