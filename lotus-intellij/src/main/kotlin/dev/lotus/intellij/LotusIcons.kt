package dev.lotus.intellij

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object LotusIcons {
    /** 16x16 lotus; `lotus_dark.svg` is picked up automatically under dark themes. */
    @JvmField
    val Lotus: Icon = IconLoader.getIcon("/icons/lotus.svg", LotusIcons::class.java)
}
