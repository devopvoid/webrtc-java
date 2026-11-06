<script setup lang="ts">
// The way of one call through the client API's threads, on the Client API Design page.

const WIDTH = 760
const HEAD_TOP = 16
const HEAD_HEIGHT = 40
const FIRST_ROW = 104
const ROW = 50

const lanes = [
  { x: 70, title: 'Your thread', kind: 'app' },
  { x: 226, title: 'Peer control', kind: 'library' },
  { x: 382, title: 'WebRTC threads', kind: 'webrtc' },
  { x: 534, title: 'Peer events', kind: 'library' },
  { x: 690, title: 'Your handlers', kind: 'app' },
]

const [app, control, webrtc, events, handlers] = [0, 1, 2, 3, 4]

const steps = [
  { from: app, to: control, label: 'peer.addTrack(camera)', note: 'returns at once' },
  { from: control, to: webrtc, label: 'addTrack', note: 'proxy call, may block control' },
  { from: webrtc, to: control, label: 'onRenegotiationNeeded', note: 'copied, posted', posted: true },
  { from: control, to: webrtc, label: 'setLocalDescription()', note: 'perfect negotiation' },
  { from: webrtc, to: control, label: 'onIceCandidate', note: 'local offer set', posted: true },
  { from: control, to: events, label: 'offer, candidates', posted: true },
  { from: events, to: handlers, label: 'Signaling.send()', note: 'offer, then candidates' },
  { from: webrtc, to: control, label: 'onConnectionChange', note: 'CONNECTED', posted: true },
  { from: control, to: events, label: 'state change', posted: true },
  { from: events, to: handlers, label: 'onStateChange()', note: 'whenConnected() completes' },
  { from: handlers, to: control, label: 'peer.close()', note: 'from a handler, fine' },
].map((step, index) => {
  const y = FIRST_ROW + index * ROW
  const from = lanes[step.from].x
  const to = lanes[step.to].x
  const direction = to > from ? 1 : -1
  const tip = to - direction * 2

  return {
    ...step,
    y,
    x1: from,
    x2: tip - direction * (step.posted ? 1 : 8),
    tip,
    back: tip - direction * 9,
    center: (from + to) / 2,
  }
})

const bottom = FIRST_ROW + (steps.length - 1) * ROW + 20
const legend = bottom + 26
const height = legend + 16
</script>

<template>
  <figure class="threading-diagram">
    <div class="scroller">
      <svg
        :viewBox="`0 0 ${WIDTH} ${height}`"
        role="img"
        aria-labelledby="threading-diagram-title threading-diagram-desc"
      >
        <title id="threading-diagram-title">How a call travels through the threads of a peer</title>
        <desc id="threading-diagram-desc">
          Your thread hands addTrack to the peer's control executor and returns at once. Control calls
          into WebRTC. WebRTC's callbacks only post to control, and control hands on to the peer's event
          executor what your handlers should hear: signaling messages, then the state change. A handler
          may close the peer, which goes to control like any other call.
        </desc>

        <g v-for="lane in lanes" :key="lane.title">
          <line class="lifeline" :x1="lane.x" :x2="lane.x" :y1="HEAD_TOP + HEAD_HEIGHT" :y2="bottom" />
          <rect
            :class="['head', lane.kind]"
            :x="lane.x - 60"
            :y="HEAD_TOP"
            width="120"
            :height="HEAD_HEIGHT"
            rx="8"
          />
          <text class="title" :x="lane.x" :y="HEAD_TOP + HEAD_HEIGHT / 2 + 4.5">{{ lane.title }}</text>
        </g>

        <g v-for="step in steps" :key="step.y">
          <line :class="['arrow', { posted: step.posted }]" :x1="step.x1" :x2="step.x2" :y1="step.y" :y2="step.y" />
          <polyline
            v-if="step.posted"
            class="head-open"
            :points="`${step.back},${step.y - 5} ${step.tip},${step.y} ${step.back},${step.y + 5}`"
          />
          <polygon
            v-else
            class="head-filled"
            :points="`${step.back},${step.y - 5} ${step.tip},${step.y} ${step.back},${step.y + 5}`"
          />
          <text class="label" :x="step.center" :y="step.y - (step.note ? 20 : 7)">{{ step.label }}</text>
          <text v-if="step.note" class="note" :x="step.center" :y="step.y - 6">{{ step.note }}</text>
        </g>

        <g :transform="`translate(${WIDTH / 2 - 150}, ${legend})`">
          <line class="arrow" x1="0" x2="26" y1="-4" y2="-4" />
          <polygon class="head-filled" points="26,-9 35,-4 26,1" />
          <text class="key" x="44" y="0">a call</text>
          <line class="arrow posted" x1="120" x2="154" y1="-4" y2="-4" />
          <polyline class="head-open" points="146,-9 155,-4 146,1" />
          <text class="key" x="164" y="0">posted, nothing waits</text>
        </g>
      </svg>
    </div>
  </figure>
</template>

<style scoped>
.threading-diagram {
  margin: 20px 0;
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
  background: var(--vp-c-bg-alt);
  --halo: var(--vp-c-bg-alt);
}

.scroller {
  overflow-x: auto;
}

svg {
  display: block;
  width: 100%;
  min-width: 580px;
  height: auto;
}

.lifeline {
  stroke: var(--vp-c-text-3);
  stroke-width: 1;
  stroke-dasharray: 2 4;
}

.head {
  stroke-width: 1;
}

.head.app {
  fill: var(--vp-c-brand-soft);
  stroke: var(--vp-c-brand-1);
}

.head.library {
  fill: var(--vp-c-bg);
  stroke: var(--vp-c-brand-1);
}

.head.webrtc {
  fill: var(--vp-c-bg-soft);
  stroke: var(--vp-c-text-3);
}

.title {
  fill: var(--vp-c-text-1);
  font-family: var(--vp-font-family-base);
  font-size: 13px;
  font-weight: 600;
  text-anchor: middle;
}

.arrow {
  stroke: var(--vp-c-text-1);
  stroke-width: 1.25;
}

.arrow.posted {
  stroke: var(--vp-c-brand-1);
  stroke-dasharray: 5 4;
}

.head-filled {
  fill: var(--vp-c-text-1);
}

.head-open {
  fill: none;
  stroke: var(--vp-c-brand-1);
  stroke-width: 1.25;
}

.label,
.note {
  paint-order: stroke;
  stroke: var(--halo);
  stroke-width: 5px;
  stroke-linejoin: round;
  text-anchor: middle;
}

.label {
  fill: var(--vp-c-text-1);
  font-family: var(--vp-font-family-mono);
  font-size: 12px;
}

.note {
  fill: var(--vp-c-text-2);
  font-family: var(--vp-font-family-base);
  font-size: 11.5px;
}

.key {
  fill: var(--vp-c-text-2);
  font-family: var(--vp-font-family-base);
  font-size: 12px;
}
</style>
