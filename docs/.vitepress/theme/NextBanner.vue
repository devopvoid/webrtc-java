<script setup lang="ts">
import { computed } from 'vue'
import { useData } from 'vitepress'
import { NEXT_BASE, SITE_URL } from '../channel'
import { PROJECT_VARS } from '../versions'

const { site } = useData()
const isNext = computed(() => site.value.base === NEXT_BASE)
</script>

<template>
  <div v-if="isNext" class="next-banner">
    These docs describe the unreleased development version {{ PROJECT_VARS.VERSION_SNAPSHOT }}.
    See the docs of the <a :href="SITE_URL">latest release, {{ PROJECT_VARS.VERSION }}</a>.
  </div>
</template>

<style scoped>
.next-banner {
  margin-bottom: 24px;
  padding: 12px 16px;
  border-radius: 8px;
  background-color: var(--vp-c-warning-soft);
  color: var(--vp-c-text-1);
  font-size: 14px;
  line-height: 1.6;
}

.next-banner a {
  color: var(--vp-c-brand-1);
  font-weight: 500;
  text-decoration: underline;
  text-underline-offset: 2px;
}
</style>
