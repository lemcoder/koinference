package io.github.lemcoder.koinference.runtime

/**
 * What a backend's runtimes produce.
 *
 * Named for the *output*, which is the axis that actually splits the runtime interfaces: a
 * vision-language model reading an image and answering in words is [TEXT], because
 * `PromptPart.ImageFile` has always let a prompt carry pixels. [IMAGE] is for a model whose reply is
 * an image.
 */
enum class Modality {
    TEXT,
    IMAGE,
}
