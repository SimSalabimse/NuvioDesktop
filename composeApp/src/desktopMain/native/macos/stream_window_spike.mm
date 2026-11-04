// Debug-only head/tail audio window reader.
// Links the FFmpeg 7.1 dylibs already shipped beside libmpv
// (libavformat 61, libavcodec 61, libavutil 59, libavfilter 10).
// Field offsets match that LP64 ABI. av*_version() rejects any other major.
// Release builds compile the JNI stubs only. The fetch is compiled in when
// NUVIO_STREAM_WINDOW_SPIKE=1 and this file is built with that macro.

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>

#if !defined(STREAM_WINDOW_SPIKE_CLI)
#include <jni.h>
#endif

#if defined(NUVIO_STREAM_WINDOW_SPIKE) || defined(STREAM_WINDOW_SPIKE_CLI)

#if defined(__APPLE__)
#include <pthread/qos.h>
#endif

static std::atomic<int> g_abort{0};
static std::atomic<int> g_busy{0};

// External C linkage so these names bind to the bundled dylibs.
struct AVFormatContext;
struct AVIOContext;
struct AVCodecContext;
struct AVCodec;
struct AVPacket;
struct AVFrame;
struct AVDictionary;
struct AVCodecParameters;
struct AVStream;
struct AVInputFormat;

struct AVRational {
    int num;
    int den;
};

struct AVIOInterruptCB {
    int (*callback)(void *);
    void *opaque;
};

extern "C" {
unsigned avformat_version(void);
unsigned avcodec_version(void);
unsigned avutil_version(void);
unsigned avfilter_version(void);
void av_log_set_level(int level);
int avformat_network_init(void);
AVFormatContext *avformat_alloc_context(void);
int avformat_open_input(AVFormatContext **ps, const char *url, AVInputFormat *fmt, AVDictionary **options);
int avformat_find_stream_info(AVFormatContext *ic, AVDictionary **options);
void avformat_close_input(AVFormatContext **s);
int av_find_best_stream(AVFormatContext *ic, int type, int wanted, int related, const AVCodec **decoder, int flags);
int av_read_frame(AVFormatContext *s, AVPacket *pkt);
int av_seek_frame(AVFormatContext *s, int stream_index, int64_t timestamp, int flags);
AVCodec *avcodec_find_decoder(int id);
AVCodecContext *avcodec_alloc_context3(const AVCodec *codec);
int avcodec_parameters_to_context(AVCodecContext *codec, const AVCodecParameters *par);
int avcodec_open2(AVCodecContext *ctx, const AVCodec *codec, AVDictionary **options);
int avcodec_send_packet(AVCodecContext *ctx, const AVPacket *pkt);
int avcodec_receive_frame(AVCodecContext *ctx, AVFrame *frame);
void avcodec_free_context(AVCodecContext **ctx);
AVPacket *av_packet_alloc(void);
void av_packet_free(AVPacket **pkt);
void av_packet_unref(AVPacket *pkt);
AVFrame *av_frame_alloc(void);
void av_frame_free(AVFrame **frame);
void av_frame_unref(AVFrame *frame);
int av_dict_set(AVDictionary **pm, const char *key, const char *value, int flags);
void av_dict_free(AVDictionary **m);
void *av_malloc(size_t size);
void av_free(void *ptr);
AVIOContext *avio_alloc_context(
    unsigned char *buffer,
    int buffer_size,
    int write_flag,
    void *opaque,
    int (*read_packet)(void *opaque, uint8_t *buf, int buf_size),
    int (*write_packet)(void *opaque, const uint8_t *buf, int buf_size),
    int64_t (*seek)(void *opaque, int64_t offset, int whence));
void avio_context_free(AVIOContext **s);
int avio_open2(AVIOContext **s, const char *url, int flags, const AVIOInterruptCB *int_cb, AVDictionary **options);
void avio_closep(AVIOContext **s);
int avio_read(AVIOContext *s, unsigned char *buf, int size);
int64_t avio_seek(AVIOContext *s, int64_t offset, int whence);
}

namespace {

constexpr int kOutSampleRate = 8000;
constexpr int64_t kProbeBytes = 2 * 1024 * 1024;
constexpr int64_t kAnalyzeDurationUs = 3 * 1000 * 1000;
constexpr int64_t kIoTimeoutUs = 15 * 1000 * 1000;

constexpr size_t OFF_FMT_PB = 32;
constexpr size_t OFF_FMT_NB_STREAMS = 44;
constexpr size_t OFF_FMT_STREAMS = 48;
constexpr size_t OFF_FMT_DURATION = 104;
constexpr size_t OFF_FMT_FLAGS = 128;
constexpr size_t OFF_STREAM_INDEX = 8;
constexpr size_t OFF_STREAM_CODECPAR = 16;
constexpr size_t OFF_STREAM_TIME_BASE = 32;
constexpr size_t OFF_PAR_CODEC_TYPE = 0;
constexpr size_t OFF_PAR_CODEC_ID = 4;
constexpr size_t OFF_PAR_FORMAT = 44;
constexpr size_t OFF_PAR_CH_LAYOUT = 128;
constexpr size_t OFF_PAR_SAMPLE_RATE = 152;
constexpr size_t OFF_FRAME_EXT = 96;
constexpr size_t OFF_FRAME_NB_SAMPLES = 112;
constexpr size_t OFF_FRAME_FORMAT = 116;
constexpr size_t OFF_FRAME_SAMPLE_RATE = 192;
constexpr size_t OFF_FRAME_CH_LAYOUT = 408;
constexpr size_t OFF_PKT_PTS = 8;
constexpr size_t OFF_PKT_DTS = 16;
constexpr size_t OFF_PKT_STREAM_INDEX = 36;
constexpr size_t OFF_IO_BUFFER = 8;
constexpr size_t OFF_IO_SEEKABLE = 144;

constexpr int kAvMediaTypeAudio = 1;
constexpr int kAvFmtFlagCustomIo = 128;
constexpr int kAvFmtNoFile = 1;
constexpr int kAvioFlagRead = 1;
constexpr int kAvseekFlagBackward = 1;
constexpr int kAvSeekSize = 65536;
constexpr int kAvLogQuiet = -8;
constexpr int kSampleFmtU8 = 0;
constexpr int kSampleFmtS16 = 1;
constexpr int kSampleFmtS32 = 2;
constexpr int kSampleFmtFlt = 3;
constexpr int kSampleFmtDbl = 4;
constexpr int kSampleFmtU8p = 5;
constexpr int kSampleFmtS16p = 6;
constexpr int kSampleFmtS32p = 7;
constexpr int kSampleFmtFltp = 8;
constexpr int kSampleFmtDblp = 9;

constexpr int kErrEof = -541478725;
constexpr int kErrExit = -1414092869;
constexpr int kErrInvalidData = -1094995529;
constexpr int kErrProtocolNotFound = -1330794744;
constexpr int kErrStreamNotFound = -1381258232;
constexpr int kErrDemuxerNotFound = -1296385272;
constexpr int kErrDecoderNotFound = -1128613112;
constexpr int kErrHttpBadRequest = -808465656;
constexpr int kErrHttpUnauthorized = -825242872;
constexpr int kErrHttpForbidden = -858797304;
constexpr int kErrHttpNotFound = -875574520;
constexpr int kErrHttpTooMany = -959591672;
constexpr int kErrHttpOther4xx = -1482175736;
constexpr int kErrHttp5xx = -1482175992;

#if EDOM > 0
#define NUVIO_AVERROR(e) (-(e))
#else
#define NUVIO_AVERROR(e) (e)
#endif

constexpr int64_t kNoPts = INT64_MIN;

struct CountedIO {
    AVIOContext *inner = nullptr;
    int64_t bytes = 0;
    int seek_failed = 0;
    int saw_playlist = 0;
};

struct WindowResult {
    int sample_rate = 0;
    int channels = 0;
    int64_t frames = 0;
    int64_t bytes_transferred = 0;
    int64_t wall_millis = 0;
    int seekable = 0;
    double duration_sec = -1.0;
    char error[64] = {0};
};

int64_t mono_now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

template <typename T>
T read_at(const void *base, size_t offset) {
    T value{};
    std::memcpy(&value, static_cast<const char *>(base) + offset, sizeof(T));
    return value;
}

void write_at(void *base, size_t offset, int value) {
    std::memcpy(static_cast<char *>(base) + offset, &value, sizeof(value));
}

void set_error(WindowResult *result, const char *code) {
    if (result->error[0] != '\0') return;
    std::snprintf(result->error, sizeof(result->error), "%s", code);
}

const char *map_open_error(int err, int seek_failed) {
    if (err == kErrExit || err == NUVIO_AVERROR(EINTR)) return "aborted";
    if (err == NUVIO_AVERROR(ECONNREFUSED)) return "connection_refused";
    if (err == NUVIO_AVERROR(ECONNRESET)) return "connection_reset";
    if (err == NUVIO_AVERROR(ETIMEDOUT) || err == NUVIO_AVERROR(EAGAIN)) return "timeout";
    if (err == NUVIO_AVERROR(ENETUNREACH) || err == NUVIO_AVERROR(EHOSTUNREACH)) return "network_unreachable";
    if (err == NUVIO_AVERROR(ENOSYS) || err == NUVIO_AVERROR(EPIPE) || err == NUVIO_AVERROR(ESPIPE)) return "no_range";
    if (err == kErrHttpUnauthorized || err == kErrHttpForbidden) return "http_forbidden";
    if (err == kErrHttpNotFound) return "http_not_found";
    if (err == kErrHttpBadRequest || err == kErrHttpTooMany || err == kErrHttpOther4xx || err == kErrHttp5xx) {
        return "http_error";
    }
    if (err == kErrProtocolNotFound) return "protocol_not_found";
    if (err == kErrDemuxerNotFound) return "demuxer_not_found";
    if (err == kErrStreamNotFound) return "no_audio";
    if (err == kErrDecoderNotFound) return "decoder_not_found";
    if (err == kErrInvalidData) return seek_failed ? "moov_or_no_range" : "invalid_data";
    if (err == NUVIO_AVERROR(EIO)) return seek_failed ? "no_range" : "io_error";
    if (err == NUVIO_AVERROR(ENOMEM)) return "out_of_memory";
    if (seek_failed) return "no_range";
    return "open_failed";
}

int interrupt_cb(void *) {
    return g_abort.load(std::memory_order_relaxed) ? 1 : 0;
}

int counted_read(void *opaque, uint8_t *buf, int buf_size) {
    auto *io = static_cast<CountedIO *>(opaque);
    if (g_abort.load(std::memory_order_relaxed)) return kErrExit;
    if (buf_size <= 0) return NUVIO_AVERROR(EINVAL);
    const int n = avio_read(io->inner, buf, buf_size);
    if (n == kErrExit || g_abort.load(std::memory_order_relaxed)) return kErrExit;
    if (n > 0) {
        io->bytes += n;
        if (!io->saw_playlist && n >= 7 && std::memcmp(buf, "#EXTM3U", 7) == 0) {
            io->saw_playlist = 1;
        }
        return n;
    }
    if (n == 0) return kErrEof;
    return n;
}

int64_t counted_seek(void *opaque, int64_t offset, int whence) {
    auto *io = static_cast<CountedIO *>(opaque);
    if (g_abort.load(std::memory_order_relaxed)) return kErrExit;
    const int64_t pos = avio_seek(io->inner, offset, whence);
    if (pos < 0 && whence != kAvSeekSize) io->seek_failed = 1;
    return pos;
}

bool abi_matches() {
    const unsigned format_major = avformat_version() >> 16;
    const unsigned codec_major = avcodec_version() >> 16;
    const unsigned util_major = avutil_version() >> 16;
    const unsigned filter_major = avfilter_version() >> 16;
    return format_major == 61 && codec_major == 61 && util_major == 59 && filter_major == 10;
}

bool url_looks_like_hls(const char *url) {
    if (url == nullptr || url[0] == '\0') return false;
    const char *query = std::strchr(url, '?');
    const char *end = query != nullptr ? query : url + std::strlen(url);
    const char *slash = url;
    for (const char *p = url; p < end; ++p) {
        if (*p == '/') slash = p + 1;
    }
    const size_t length = static_cast<size_t>(end - slash);
    if (length >= 5 && std::strncmp(end - 5, ".m3u8", 5) == 0) return true;
    if (length >= 4 && std::strncmp(end - 4, ".m3u", 4) == 0) return true;
    return false;
}

int channel_count(const void *layout_base) {
    if (layout_base == nullptr) return 0;
    return read_at<int>(layout_base, 4);
}

float mix_sample(int format, uint8_t **planes, int channels, int index, bool *ok) {
    *ok = false;
    if (planes == nullptr || channels <= 0) return 0.f;
    double sum = 0.0;
    switch (format) {
        case kSampleFmtFlt:
            if (planes[0] == nullptr) return 0.f;
            for (int c = 0; c < channels; ++c) {
                float sample = 0.f;
                std::memcpy(&sample, planes[0] + (static_cast<size_t>(index) * channels + c) * sizeof(float), sizeof(sample));
                sum += sample;
            }
            break;
        case kSampleFmtFltp:
            for (int c = 0; c < channels; ++c) {
                if (planes[c] == nullptr) return 0.f;
                float sample = 0.f;
                std::memcpy(&sample, planes[c] + static_cast<size_t>(index) * sizeof(float), sizeof(sample));
                sum += sample;
            }
            break;
        case kSampleFmtS16: {
            if (planes[0] == nullptr) return 0.f;
            for (int c = 0; c < channels; ++c) {
                int16_t sample = 0;
                std::memcpy(&sample, planes[0] + (static_cast<size_t>(index) * channels + c) * sizeof(sample), sizeof(sample));
                sum += sample / 32768.0;
            }
            break;
        }
        case kSampleFmtS16p:
            for (int c = 0; c < channels; ++c) {
                if (planes[c] == nullptr) return 0.f;
                int16_t sample = 0;
                std::memcpy(&sample, planes[c] + static_cast<size_t>(index) * sizeof(sample), sizeof(sample));
                sum += sample / 32768.0;
            }
            break;
        case kSampleFmtS32: {
            if (planes[0] == nullptr) return 0.f;
            for (int c = 0; c < channels; ++c) {
                int32_t sample = 0;
                std::memcpy(&sample, planes[0] + (static_cast<size_t>(index) * channels + c) * sizeof(sample), sizeof(sample));
                sum += sample / 2147483648.0;
            }
            break;
        }
        case kSampleFmtS32p:
            for (int c = 0; c < channels; ++c) {
                if (planes[c] == nullptr) return 0.f;
                int32_t sample = 0;
                std::memcpy(&sample, planes[c] + static_cast<size_t>(index) * sizeof(sample), sizeof(sample));
                sum += sample / 2147483648.0;
            }
            break;
        case kSampleFmtU8: {
            if (planes[0] == nullptr) return 0.f;
            for (int c = 0; c < channels; ++c) {
                sum += (static_cast<int>(planes[0][static_cast<size_t>(index) * channels + c]) - 128) / 128.0;
            }
            break;
        }
        case kSampleFmtU8p:
            for (int c = 0; c < channels; ++c) {
                if (planes[c] == nullptr) return 0.f;
                sum += (static_cast<int>(planes[c][index]) - 128) / 128.0;
            }
            break;
        case kSampleFmtDbl: {
            if (planes[0] == nullptr) return 0.f;
            for (int c = 0; c < channels; ++c) {
                double sample = 0.0;
                std::memcpy(&sample, planes[0] + (static_cast<size_t>(index) * channels + c) * sizeof(sample), sizeof(sample));
                sum += sample;
            }
            break;
        }
        case kSampleFmtDblp:
            for (int c = 0; c < channels; ++c) {
                if (planes[c] == nullptr) return 0.f;
                double sample = 0.0;
                std::memcpy(&sample, planes[c] + static_cast<size_t>(index) * sizeof(sample), sizeof(sample));
                sum += sample;
            }
            break;
        default:
            return 0.f;
    }
    *ok = true;
    return static_cast<float>(sum / channels);
}

struct Resampler {
    int in_rate = 0;
    double out_pos = 0.0;
    int64_t in_count = 0;
    float previous = 0.f;
    int has_previous = 0;
    int64_t frames = 0;
    int64_t limit = 0;
};

void push_sample(Resampler *resampler, float sample) {
    if (resampler->frames >= resampler->limit) return;
    const int64_t index = resampler->in_count;
    if (resampler->has_previous) {
        const double step = static_cast<double>(resampler->in_rate) / static_cast<double>(kOutSampleRate);
        while (resampler->out_pos <= static_cast<double>(index) && resampler->frames < resampler->limit) {
            double t = resampler->out_pos - static_cast<double>(index - 1);
            if (t < 0.0) t = 0.0;
            if (t > 1.0) t = 1.0;
            (void)(resampler->previous + static_cast<float>(t) * (sample - resampler->previous));
            resampler->frames += 1;
            resampler->out_pos += step;
        }
    }
    resampler->previous = sample;
    resampler->has_previous = 1;
    resampler->in_count = index + 1;
}

double packet_seconds(const AVPacket *packet, AVRational time_base) {
    int64_t stamp = read_at<int64_t>(packet, OFF_PKT_PTS);
    if (stamp == kNoPts) stamp = read_at<int64_t>(packet, OFF_PKT_DTS);
    if (stamp == kNoPts || time_base.den == 0) return -1.0;
    return static_cast<double>(stamp) * static_cast<double>(time_base.num) / static_cast<double>(time_base.den);
}

void analyze_stream_window(const char *url, double start_sec, double duration_sec, WindowResult *result) {
    struct Busy {
        Busy() { g_busy.fetch_add(1, std::memory_order_relaxed); }
        ~Busy() { g_busy.fetch_sub(1, std::memory_order_relaxed); }
    } busy;
    const int64_t started = mono_now_ms();
#if defined(__APPLE__)
    pthread_set_qos_class_self_np(QOS_CLASS_BACKGROUND, 0);
#endif

    AVFormatContext *fmt = nullptr;
    AVIOContext *outer = nullptr;
    AVIOContext *inner = nullptr;
    unsigned char *io_buffer = nullptr;
    AVCodecContext *decoder = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    AVDictionary *io_opts = nullptr;
    AVDictionary *fmt_opts = nullptr;
    CountedIO counted;
    *result = WindowResult{};

    auto finish = [&]() {
        result->bytes_transferred = counted.bytes;
        result->wall_millis = mono_now_ms() - started;
        if (frame) av_frame_free(&frame);
        if (packet) av_packet_free(&packet);
        if (decoder) avcodec_free_context(&decoder);
        if (fmt) avformat_close_input(&fmt);
        if (outer) {
            unsigned char *buffer = read_at<unsigned char *>(outer, OFF_IO_BUFFER);
            avio_context_free(&outer);
            av_free(buffer);
        } else if (io_buffer) {
            av_free(io_buffer);
        }
        if (inner) avio_closep(&inner);
        av_dict_free(&io_opts);
        av_dict_free(&fmt_opts);
    };

    if (url == nullptr || url[0] == '\0') {
        set_error(result, "empty_url");
        finish();
        return;
    }
    if (start_sec < 0.0 || duration_sec <= 0.0) {
        set_error(result, "bad_args");
        finish();
        return;
    }
    if (g_abort.load(std::memory_order_relaxed)) {
        set_error(result, "aborted");
        finish();
        return;
    }
    if (!abi_matches()) {
        set_error(result, "ffmpeg_abi_mismatch");
        finish();
        return;
    }
    if (url_looks_like_hls(url)) {
        set_error(result, "hls_or_nofile");
        finish();
        return;
    }

    av_log_set_level(kAvLogQuiet);
    avformat_network_init();

    AVIOInterruptCB interrupt{interrupt_cb, nullptr};
    av_dict_set(&io_opts, "rw_timeout", "15000000", 0);
    av_dict_set(&io_opts, "connect_timeout", "15000000", 0);
    char timeout_buf[32];
    std::snprintf(timeout_buf, sizeof(timeout_buf), "%lld", static_cast<long long>(kIoTimeoutUs));
    av_dict_set(&io_opts, "timeout", timeout_buf, 0);
    const int open_io = avio_open2(&inner, url, kAvioFlagRead, &interrupt, &io_opts);
    if (open_io < 0 || inner == nullptr) {
        set_error(result, map_open_error(open_io, 0));
        finish();
        return;
    }
    counted.inner = inner;
    result->seekable = (read_at<int>(inner, OFF_IO_SEEKABLE) & 1) ? 1 : 0;

    io_buffer = static_cast<unsigned char *>(av_malloc(32 * 1024));
    if (io_buffer == nullptr) {
        set_error(result, "out_of_memory");
        finish();
        return;
    }
    outer = avio_alloc_context(io_buffer, 32 * 1024, 0, &counted, counted_read, nullptr, counted_seek);
    if (outer == nullptr) {
        set_error(result, "out_of_memory");
        finish();
        return;
    }
    io_buffer = nullptr;

    fmt = avformat_alloc_context();
    if (fmt == nullptr) {
        set_error(result, "out_of_memory");
        finish();
        return;
    }
    std::memcpy(static_cast<char *>(static_cast<void *>(fmt)) + OFF_FMT_PB, &outer, sizeof(outer));
    const int flags = read_at<int>(fmt, OFF_FMT_FLAGS) | kAvFmtFlagCustomIo;
    write_at(fmt, OFF_FMT_FLAGS, flags);
    char probe_buf[32];
    std::snprintf(probe_buf, sizeof(probe_buf), "%lld", static_cast<long long>(kProbeBytes));
    av_dict_set(&fmt_opts, "probesize", probe_buf, 0);
    av_dict_set(&fmt_opts, "formatprobesize", "262144", 0);
    av_dict_set(&fmt_opts, "duration_probesize", probe_buf, 0);
    char analyze_buf[32];
    std::snprintf(analyze_buf, sizeof(analyze_buf), "%lld", static_cast<long long>(kAnalyzeDurationUs));
    av_dict_set(&fmt_opts, "analyzeduration", analyze_buf, 0);

    // NULL url: the demuxer must use the counted IO. A playlist extension would
    // otherwise select HLS, which opens the network itself and skips the counter.
    const int opened = avformat_open_input(&fmt, nullptr, nullptr, &fmt_opts);
    if (opened < 0 || fmt == nullptr) {
        if (counted.saw_playlist) {
            set_error(result, "hls_or_nofile");
        } else {
            set_error(result, map_open_error(opened, counted.seek_failed));
        }
        finish();
        return;
    }

    const int info = avformat_find_stream_info(fmt, nullptr);
    if (info < 0) {
        set_error(result, counted.saw_playlist ? "hls_or_nofile" : map_open_error(info, counted.seek_failed));
        finish();
        return;
    }
    result->seekable = (read_at<int>(inner, OFF_IO_SEEKABLE) & 1) ? 1 : 0;

    auto *input_format = read_at<AVInputFormat *>(fmt, 8);
    if (input_format != nullptr) {
        const int format_flags = read_at<int>(input_format, 16);
        const char *name = read_at<const char *>(input_format, 0);
        if ((format_flags & kAvFmtNoFile) != 0 || (name != nullptr && std::strcmp(name, "hls") == 0)) {
            set_error(result, "hls_or_nofile");
            finish();
            return;
        }
    }

    const int64_t duration = read_at<int64_t>(fmt, OFF_FMT_DURATION);
    if (duration != kNoPts && duration > 0) {
        result->duration_sec = static_cast<double>(duration) / 1000000.0;
    }

    const int audio_index = av_find_best_stream(fmt, kAvMediaTypeAudio, -1, -1, nullptr, 0);
    if (audio_index < 0) {
        set_error(result, "no_audio");
        finish();
        return;
    }
    const unsigned nb_streams = read_at<unsigned>(fmt, OFF_FMT_NB_STREAMS);
    auto **streams = read_at<AVStream **>(fmt, OFF_FMT_STREAMS);
    if (streams == nullptr || static_cast<unsigned>(audio_index) >= nb_streams || streams[audio_index] == nullptr) {
        set_error(result, "no_audio");
        finish();
        return;
    }
    AVStream *stream = streams[audio_index];
    auto *par = read_at<AVCodecParameters *>(stream, OFF_STREAM_CODECPAR);
    if (par == nullptr || read_at<int>(par, OFF_PAR_CODEC_TYPE) != kAvMediaTypeAudio) {
        set_error(result, "no_audio");
        finish();
        return;
    }
    const AVRational time_base = read_at<AVRational>(stream, OFF_STREAM_TIME_BASE);
    AVCodec *codec = avcodec_find_decoder(read_at<int>(par, OFF_PAR_CODEC_ID));
    if (codec == nullptr) {
        set_error(result, "decoder_not_found");
        finish();
        return;
    }
    decoder = avcodec_alloc_context3(codec);
    if (decoder == nullptr || avcodec_parameters_to_context(decoder, par) < 0 || avcodec_open2(decoder, codec, nullptr) < 0) {
        set_error(result, "decoder_not_found");
        finish();
        return;
    }
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    if (packet == nullptr || frame == nullptr) {
        set_error(result, "out_of_memory");
        finish();
        return;
    }

    if (g_abort.load(std::memory_order_relaxed)) {
        set_error(result, "aborted");
        finish();
        return;
    }
    if (start_sec > 0.05 && !result->seekable) {
        set_error(result, "not_seekable");
        finish();
        return;
    }
    const int64_t seek_target = static_cast<int64_t>(start_sec * 1000000.0);
    const int seek_rc = av_seek_frame(fmt, -1, seek_target, kAvseekFlagBackward);
    if (seek_rc < 0) {
        if (start_sec <= 0.05) {
            // A non-seekable head can still be read from the probe position.
        } else {
            set_error(result, counted.seek_failed || !result->seekable ? "no_range" : "seek_failed");
            finish();
            return;
        }
    }

    Resampler resampler;
    resampler.limit = static_cast<int64_t>(duration_sec * static_cast<double>(kOutSampleRate)) + 8;
    if (resampler.limit < 1) resampler.limit = 1;
    const double end_sec = start_sec + duration_sec;
    bool saw_unsupported = false;
    bool counting = start_sec <= 0.05;

    auto consume = [&](bool count_samples) {
        const int format = read_at<int>(frame, OFF_FRAME_FORMAT);
        const int nb_samples = read_at<int>(frame, OFF_FRAME_NB_SAMPLES);
        int rate = read_at<int>(frame, OFF_FRAME_SAMPLE_RATE);
        int channels = channel_count(static_cast<const char *>(static_cast<void *>(frame)) + OFF_FRAME_CH_LAYOUT);
        if (rate <= 0) rate = read_at<int>(par, OFF_PAR_SAMPLE_RATE);
        if (channels <= 0) channels = channel_count(static_cast<const char *>(static_cast<void *>(par)) + OFF_PAR_CH_LAYOUT);
        if (nb_samples <= 0) return;
        if (rate <= 0 || rate > 384000 || channels <= 0 || channels > 32 || nb_samples > (1 << 20)) {
            saw_unsupported = true;
            return;
        }
        if (resampler.in_rate == 0) {
            resampler.in_rate = rate;
            result->sample_rate = kOutSampleRate;
            result->channels = 1;
        } else if (resampler.in_rate != rate) {
            saw_unsupported = true;
            return;
        }
        if (!count_samples) return;
        auto **planes = read_at<uint8_t **>(frame, OFF_FRAME_EXT);
        for (int i = 0; i < nb_samples; ++i) {
            bool ok = false;
            const float mixed = mix_sample(format, planes, channels, i, &ok);
            if (!ok) {
                saw_unsupported = true;
                return;
            }
            push_sample(&resampler, mixed);
            if (resampler.frames >= resampler.limit) return;
        }
    };

    while (!g_abort.load(std::memory_order_relaxed) && resampler.frames < resampler.limit) {
        const int read_rc = av_read_frame(fmt, packet);
        if (read_rc < 0) {
            if (read_rc == kErrExit || g_abort.load(std::memory_order_relaxed)) set_error(result, "aborted");
            break;
        }
        const int stream_index = read_at<int>(packet, OFF_PKT_STREAM_INDEX);
        if (stream_index != audio_index) {
            av_packet_unref(packet);
            continue;
        }
        const double when = packet_seconds(packet, time_base);
        if (when >= 0.0 && when + 0.25 < start_sec) {
            counting = false;
        } else if (when < 0.0) {
            counting = start_sec <= 0.05 || counting;
        } else if (when >= end_sec) {
            av_packet_unref(packet);
            break;
        } else {
            counting = true;
        }
        const int sent = avcodec_send_packet(decoder, packet);
        av_packet_unref(packet);
        if (sent < 0 && sent != NUVIO_AVERROR(EAGAIN) && sent != kErrEof) continue;
        while (resampler.frames < resampler.limit) {
            const int received = avcodec_receive_frame(decoder, frame);
            if (received == NUVIO_AVERROR(EAGAIN) || received == kErrEof) break;
            if (received < 0) break;
            consume(counting);
            av_frame_unref(frame);
            if (saw_unsupported) break;
        }
        if (saw_unsupported) break;
    }

    if (g_abort.load(std::memory_order_relaxed)) set_error(result, "aborted");
    if (!saw_unsupported && result->error[0] == '\0') {
        avcodec_send_packet(decoder, nullptr);
        while (resampler.frames < resampler.limit) {
            const int received = avcodec_receive_frame(decoder, frame);
            if (received < 0) break;
            consume(counting);
            av_frame_unref(frame);
        }
    }
    result->frames = resampler.frames;
    if (saw_unsupported) set_error(result, "unsupported_sample_fmt");
    else if (result->error[0] == '\0' && result->frames <= 0) set_error(result, "decode_failed");
    finish();
}

void write_result_json(const WindowResult &result, char *out, size_t out_size) {
    const char *error = result.error[0] == '\0' ? "null" : nullptr;
    if (error != nullptr) {
        std::snprintf(
            out,
            out_size,
            "{\"sampleRate\":%d,\"channels\":%d,\"frames\":%lld,\"bytesTransferred\":%lld,"
            "\"wallMillis\":%lld,\"seekable\":%d,\"durationSec\":%.3f,\"error\":null}",
            result.sample_rate,
            result.channels,
            static_cast<long long>(result.frames),
            static_cast<long long>(result.bytes_transferred),
            static_cast<long long>(result.wall_millis),
            result.seekable,
            result.duration_sec);
        return;
    }
    std::snprintf(
        out,
        out_size,
        "{\"sampleRate\":%d,\"channels\":%d,\"frames\":%lld,\"bytesTransferred\":%lld,"
        "\"wallMillis\":%lld,\"seekable\":%d,\"durationSec\":%.3f,\"error\":\"%s\"}",
        result.sample_rate,
        result.channels,
        static_cast<long long>(result.frames),
        static_cast<long long>(result.bytes_transferred),
        static_cast<long long>(result.wall_millis),
        result.seekable,
        result.duration_sec,
        result.error);
}

}  // namespace

#endif

#if !defined(STREAM_WINDOW_SPIKE_CLI) && !defined(NUVIO_STREAM_WINDOW_SPIKE)

static std::atomic<int> g_abort_stub{0};
static std::atomic<int> g_busy_stub{0};

#endif

#if !defined(STREAM_WINDOW_SPIKE_CLI)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_streamWindowSpikeCompiled(
    JNIEnv *, jobject
) {
#if defined(NUVIO_STREAM_WINDOW_SPIKE)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_abortStreamWindow(
    JNIEnv *, jobject
) {
#if defined(NUVIO_STREAM_WINDOW_SPIKE)
    g_abort.store(1, std::memory_order_relaxed);
#else
    g_abort_stub.store(1, std::memory_order_relaxed);
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearStreamWindowAbort(
    JNIEnv *, jobject
) {
#if defined(NUVIO_STREAM_WINDOW_SPIKE)
    g_abort.store(0, std::memory_order_relaxed);
#else
    g_abort_stub.store(0, std::memory_order_relaxed);
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_streamWindowSpikeBusy(
    JNIEnv *, jobject
) {
#if defined(NUVIO_STREAM_WINDOW_SPIKE)
    return g_busy.load(std::memory_order_relaxed) > 0 ? JNI_TRUE : JNI_FALSE;
#else
    return g_busy_stub.load(std::memory_order_relaxed) > 0 ? JNI_TRUE : JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_analyzeStreamWindow(
    JNIEnv *env, jobject, jstring url, jdouble start_sec, jdouble duration_sec
) {
#if !defined(NUVIO_STREAM_WINDOW_SPIKE)
    (void)url;
    (void)start_sec;
    (void)duration_sec;
    return env->NewStringUTF(
        "{\"sampleRate\":0,\"channels\":0,\"frames\":0,\"bytesTransferred\":0,"
        "\"wallMillis\":0,\"seekable\":0,\"durationSec\":-1.000,\"error\":\"spike_not_in_build\"}");
#else
    const char *chars = url != nullptr ? env->GetStringUTFChars(url, nullptr) : nullptr;
    WindowResult result;
    analyze_stream_window(chars != nullptr ? chars : "", start_sec, duration_sec, &result);
    if (chars != nullptr) env->ReleaseStringUTFChars(url, chars);
    char json[512];
    write_result_json(result, json, sizeof(json));
    return env->NewStringUTF(json);
#endif
}

#endif

#if defined(STREAM_WINDOW_SPIKE_CLI)
int main(int argc, char **argv) {
    if (argc != 4) {
        std::fprintf(stderr, "usage: stream_window_spike <url> <startSec> <durationSec>\n");
        return 2;
    }
    char *end_start = nullptr;
    char *end_duration = nullptr;
    const double start_sec = std::strtod(argv[2], &end_start);
    const double duration_sec = std::strtod(argv[3], &end_duration);
    if (end_start == argv[2] || end_duration == argv[3]) {
        std::fprintf(stderr, "usage: stream_window_spike <url> <startSec> <durationSec>\n");
        return 2;
    }
    WindowResult result;
    analyze_stream_window(argv[1], start_sec, duration_sec, &result);
    char json[512];
    write_result_json(result, json, sizeof(json));
    std::puts(json);
    return 0;
}
#endif
