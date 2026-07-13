package com.dot.gallery.feature_node.presentation.securereview

internal sealed interface SecureReviewEffect {

    data object Finish : SecureReviewEffect
}
