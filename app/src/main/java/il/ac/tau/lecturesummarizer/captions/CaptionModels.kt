package il.ac.tau.lecturesummarizer.captions

/** Model file names, sizes, and Hugging Face download URLs for on-device captions. */
object CaptionModels {
    const val HF_ZIPFORMER = "https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he/resolve/main/sherpa-onnx"
    const val HF_DICTALM =
        "https://huggingface.co/EMD123/DictaLM-3.0-1.7B-Instruct-Q4_K_M-GGUF/resolve/main/dictalm-3.0-1.7b-instruct-q4_k_m.gguf"

    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val GGUF = "dictalm-3.0-1.7b-instruct-q4_k_m.gguf"

    /** Exact published byte sizes (completeness check). */
    val ZIPFORMER_SIZES = mapOf(
        ENCODER to 70_478_964L,
        DECODER to 1_049_105L,
        JOINER to 771_805L,
        TOKENS to 17_086L,
    )
    const val GGUF_BYTES = 1_107_404_704L

    /** Disk headroom beyond GGUF for partials + Zipformer. */
    const val DISK_HEADROOM_BYTES = 256L * 1024 * 1024
    const val ZIPFORMER_TOTAL_BYTES = 70_478_964L + 1_049_105L + 771_805L + 17_086L
    const val DOWNLOAD_TOTAL_BYTES = ZIPFORMER_TOTAL_BYTES + GGUF_BYTES

    /** Free RAM required before loading cleanup LM with speech. */
    const val CLEANUP_FREE_RAM_BYTES = 2_500L * 1024 * 1024

    const val SAMPLE_RATE = 16_000
}
