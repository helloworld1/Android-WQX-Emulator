package org.liberty.android.nc1020emu

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.navigation.ui.NavigationUI

class AboutFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.about_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar: Toolbar = view.findViewById(R.id.toolbar)
        val navController = Navigation.findNavController(view)
        NavigationUI.setupWithNavController(toolbar, navController)
        toolbar.title = getString(R.string.app_name)

        val scrollView: View = view.findViewById(R.id.about_scroll_view)
        val initialToolbarLeft = toolbar.paddingLeft
        val initialToolbarRight = toolbar.paddingRight
        val initialToolbarBottom = toolbar.paddingBottom
        val initialScrollLeft = scrollView.paddingLeft
        val initialScrollTop = scrollView.paddingTop
        val initialScrollRight = scrollView.paddingRight
        val initialScrollBottom = scrollView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            toolbar.setPadding(
                initialToolbarLeft + bars.left,
                bars.top,
                initialToolbarRight + bars.right,
                initialToolbarBottom
            )
            scrollView.setPadding(
                initialScrollLeft + bars.left,
                initialScrollTop,
                initialScrollRight + bars.right,
                initialScrollBottom + bars.bottom
            )
            insets
        }

        val versionText: TextView = view.findViewById(R.id.version_text)
        val context = requireContext()
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val versionName = packageInfo.versionName ?: ""
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        versionText.text = "$versionName ($versionCode)"

        val githubRepoItem: View = view.findViewById(R.id.github_repo_item)
        val repoUrl = getString(R.string.github_repo_url)
        githubRepoItem.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl)))
        }
    }
}
