<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { withBase } from 'vitepress'
import { PROJECT_VARS } from '../versions'
import NextBanner from './NextBanner.vue'
import DocsIcon from './DocsIcon.vue'

const tab = ref('client')
const copied = ref(false)
const copyFailed = ref(false)
const platforms = [
  { classifier: 'windows-x86_64', label: 'Windows · x64' },
  { classifier: 'windows-aarch64', label: 'Windows · ARM64' },
  { classifier: 'macos-x86_64', label: 'macOS · Intel' },
  { classifier: 'macos-aarch64', label: 'macOS · Apple Silicon' },
  { classifier: 'linux-x86_64', label: 'Linux · x64' },
  { classifier: 'linux-aarch64', label: 'Linux · ARM64' },
  { classifier: 'linux-aarch32', label: 'Linux · ARM32' },
]
const platform = ref(platforms[0].classifier)
const dependency = computed(() => `<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>${PROJECT_VARS.VERSION}</version>
</dependency>

<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>${PROJECT_VARS.VERSION}</version>
    <classifier>${platform.value}</classifier>
    <scope>runtime</scope>
</dependency>`)
const gradleDependency = computed(() => `dependencies {
    implementation("dev.onvoid.webrtc:webrtc-java:${PROJECT_VARS.VERSION}")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:${PROJECT_VARS.VERSION}:${platform.value}")
}`)
const displayedDependency = computed(() => tab.value === 'gradle' ? gradleDependency.value : dependency.value)
watch([tab, platform], () => {
  copied.value = false
  copyFailed.value = false
})
async function copyDependency() {
  try {
    await navigator.clipboard.writeText(displayedDependency.value)
    copied.value = true
    copyFailed.value = false
    setTimeout(() => { copied.value = false }, 2000)
  } catch { copyFailed.value = true }
}
const paths = [
  { label: 'START BUILDING', title: 'Your first connection', description: 'Install the library, connect two peers, and send your first audio and video.', link: '/guide/get-started', action: 'Get started', icon: 'code' },
  { label: 'EXPLORE MEDIA', title: 'Bring your media', description: 'Capture a camera, share a screen, play an RTSP stream, or record a call.', link: '/guide/media/media-devices', action: 'Explore media', icon: 'video' },
  { label: 'CONNECT TO MEDIA SERVERS', title: 'WHIP / WHEP', description: 'Publish live audio and video with WHIP, or receive streams with WHEP over HTTP.', link: '/guide/client-api#whip-and-whep', action: 'Explore WHIP / WHEP', icon: 'arrows' },
]
const topics = [
  { title: 'Audio & video capture', detail: 'Connect microphones, cameras, and custom sources.', link: '/guide/media/media-devices', icon: 'video' },
  { title: 'Screen sharing', detail: 'From a window to the whole desktop.', link: '/guide/video/desktop-capture', icon: 'monitor' },
  { title: 'Data channels', detail: 'Reliable or unreliable messaging and file transfer.', link: '/guide/data/data-channels', icon: 'arrows' },
  { title: 'Hardware codecs', detail: 'GPU encoding and decoding, with software fallback.', link: '/guide/advanced/video-codecs#hardware-encoding', icon: 'zap' },
  { title: 'Custom video codecs', detail: 'Plug your own Java encoders and decoders into WebRTC.', link: '/guide/advanced/video-codecs#adding-a-codec', icon: 'code' },
  { title: 'Video processing', detail: 'Transform camera and desktop frames before sending.', link: '/guide/video/camera-capture#changing-frames-before-they-are-sent', icon: 'sliders' },
  { title: 'End-to-end encryption', detail: 'Build frame encryption with encoded transforms.', link: '/guide/advanced/encoded-transforms', icon: 'lock' },
  { title: 'Pre-encoded media', detail: 'Send encoded frames without encoding them again.', link: '/guide/advanced/encoded-transforms#injecting-encoded-frames', icon: 'send' },
  { title: 'Media playback', detail: 'Play media files and live RTSP streams into a call.', link: '/guide/media/media-files', icon: 'play' },
  { title: 'Call recording', detail: 'Save sent and received media without re-encoding.', link: '/guide/media/media-recording', icon: 'record' },
  { title: 'Audio processing', detail: 'Echo cancellation, noise suppression, and gain control.', link: '/guide/audio/audio-processing', icon: 'audio' },
  { title: 'Statistics & monitoring', detail: 'Track bitrate, packet loss, jitter, and round-trip time.', link: '/guide/monitoring/rtc-stats', icon: 'activity' },
]
</script>

<template>
  <div class="docs-home">
    <NextBanner />
    <section class="dh-hero" aria-labelledby="hero-title">
      <div class="dh-intro">
        <h1 id="hero-title">Real-time.<br>Native.<br><span>Made for Java.</span></h1>
        <p class="dh-lead">Audio, video, and data for your desktop applications. The power of WebRTC, with a Java API that feels like home.</p>
        <div class="dh-actions"><a class="dh-primary" :href="withBase('/guide/get-started')">Start building</a><a class="dh-secondary" :href="withBase('/guide/examples')">Explore examples <DocsIcon name="arrow-up-right" /></a></div>
        <div class="dh-platforms"><span>ONE API. THREE PLATFORMS.</span><div>Windows <i>·</i> macOS <i>·</i> Linux</div></div>
      </div>
      <div class="dh-code-area">
        <div class="dh-code-window">
          <div class="dh-code-title"><span><i></i><i></i><i></i></span><span class="dh-file-type">{{ tab === 'client' ? 'JAVA' : tab === 'maven' ? 'XML' : 'GRADLE' }}</span></div>
          <div class="dh-code-tabs" role="group" aria-label="Code example"><button :class="{ active: tab === 'client' }" :aria-pressed="tab === 'client'" @click="tab = 'client'">Client API</button><button :class="{ active: tab === 'maven' }" :aria-pressed="tab === 'maven'" @click="tab = 'maven'">Maven dependency</button><button :class="{ active: tab === 'gradle' }" :aria-pressed="tab === 'gradle'" @click="tab = 'gradle'">Gradle</button></div>
          <pre v-if="tab === 'client'" class="dh-code"><code><span class="comment">// Your media. Connected.</span>
<span class="keyword">try</span> (<span class="type">WebRtc</span> rtc = <span class="type">WebRtc</span>.create()) {
    <span class="type">Peer</span> peer = rtc.createPeer(
        <span class="type">PeerOptions</span>.builder()
            .polite(<span class="keyword">true</span>)
            .signaling(message -&gt;
                socket.send(message.toJson()))
            .build());

    socket.onText(text -&gt; peer.signal(
        <span class="type">SignalMessage</span>.fromJson(text)));

    peer.addTrack(rtc.media().microphone());
    peer.addTrack(rtc.media().camera());

    peer.whenConnected().join();
    hangUp.await();
} <span class="comment">// Everything closes with the runtime.</span></code></pre>
          <div v-else class="dh-maven">
            <pre class="dh-code"><code>{{ displayedDependency }}</code></pre>
            <div class="dh-copy-row">
              <button class="dh-copy" @click="copyDependency">{{ copied ? 'Copied ✓' : 'Copy dependencies' }}</button>
              <select id="native-platform" class="dh-platform-select" aria-label="Native platform" v-model="platform">
                <option v-for="option in platforms" :key="option.classifier" :value="option.classifier">{{ option.label }}</option>
              </select>
              <span v-if="copyFailed" role="status">Select the dependencies above to copy them manually.</span>
            </div>
          </div>
          <div class="dh-code-footer"><span><span class="dh-dot"></span> {{ tab === 'client' ? 'Excerpt · bring your signaling transport' : `Release ${PROJECT_VARS.VERSION}` }}</span></div>
        </div>
        <div class="dh-code-caption"><span>Less plumbing. More building.</span> Lifecycle and negotiation, handled.</div>
      </div>
    </section>

    <div class="dh-capabilities"><span><b>JNI</b> Native performance</span><span><DocsIcon name="arrows" /> Peer-to-peer connections</span><span><DocsIcon name="shield" /> Open source · Apache 2.0</span><a :href="withBase('/guide/client-api')">Meet the Client API <DocsIcon name="arrow-right" /></a></div>

    <section class="dh-section" aria-labelledby="path-title">
      <div class="dh-section-heading"><div><h2 id="path-title">Find your starting point.</h2></div><a :href="withBase('/guide/')">Browse all guides <DocsIcon name="arrow-up-right" /></a></div>
      <div class="dh-paths"><a v-for="path in paths" :key="path.link" class="dh-path" :href="withBase(path.link)"><div class="dh-path-top"><span class="dh-path-icon"><DocsIcon :name="path.icon" /></span></div><div class="dh-eyebrow">{{ path.label }}</div><h3>{{ path.title }}</h3><p>{{ path.description }}</p><div class="dh-path-action">{{ path.action }}</div></a></div>
    </section>

    <section class="dh-section dh-topics" aria-labelledby="topics-title"><div class="dh-section-heading"><div><h2 id="topics-title">What will you connect?</h2></div><span class="dh-muted">From the first frame to the last packet.</span></div><div class="dh-topic-grid"><a v-for="topic in topics" :key="topic.title" :href="withBase(topic.link)"><span class="dh-topic-icon"><DocsIcon :name="topic.icon" /></span><div><h3>{{ topic.title }}</h3><p>{{ topic.detail }}</p></div><DocsIcon class="dh-topic-arrow" name="arrow-up-right" /></a></div></section>
    <footer class="dh-footer"><span>webrtc-java <span class="dh-muted">/ Real-time starts here.</span></span><div><a :href="withBase('/guide/build')">Build from source</a><a href="https://javadoc.io/doc/dev.onvoid.webrtc/webrtc-java/latest/index.html">JavaDoc <DocsIcon name="arrow-up-right" /></a></div></footer>
  </div>
</template>
