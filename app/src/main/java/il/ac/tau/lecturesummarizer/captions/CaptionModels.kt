package il.ac.tau.lecturesummarizer.captions

/** Model file names, sizes, and Hugging Face download URLs for on-device captions. */
object CaptionModels {
    const val HF_ZIPFORMER = "https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he/resolve/main/sherpa-onnx"
    const val HF_ZIPFORMER_EN =
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main"
    const val HF_DICTALM =
        "https://huggingface.co/EMD123/DictaLM-3.0-1.7B-Instruct-Q4_K_M-GGUF/resolve/main/dictalm-3.0-1.7b-instruct-q4_k_m.gguf"

    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val GGUF = "dictalm-3.0-1.7b-instruct-q4_k_m.gguf"

    const val EN_ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
    const val EN_DECODER = "decoder-epoch-99-avg-1.int8.onnx"
    const val EN_JOINER = "joiner-epoch-99-avg-1.int8.onnx"
    const val EN_TOKENS = "tokens.txt"

    /** Exact published byte sizes (completeness check). */
    val ZIPFORMER_SIZES = mapOf(
        ENCODER to 70_478_964L,
        DECODER to 1_049_105L,
        JOINER to 771_805L,
        TOKENS to 17_086L,
    )
    val ZIPFORMER_EN_SIZES = mapOf(
        EN_ENCODER to 42_845_182L,
        EN_DECODER to 539_499L,
        EN_JOINER to 259_572L,
        EN_TOKENS to 5_048L,
    )
    const val GGUF_BYTES = 1_107_404_704L

    /** Disk headroom beyond GGUF for partials + Zipformer. */
    const val DISK_HEADROOM_BYTES = 256L * 1024 * 1024
    const val ZIPFORMER_TOTAL_BYTES = 70_478_964L + 1_049_105L + 771_805L + 17_086L
    const val ZIPFORMER_EN_TOTAL_BYTES = 42_845_182L + 539_499L + 259_572L + 5_048L
    const val DOWNLOAD_TOTAL_BYTES = ZIPFORMER_TOTAL_BYTES + ZIPFORMER_EN_TOTAL_BYTES + GGUF_BYTES

    /** Free RAM required before loading cleanup LM with speech. */
    const val CLEANUP_FREE_RAM_BYTES = 2_500L * 1024 * 1024

    const val SAMPLE_RATE = 16_000
}
