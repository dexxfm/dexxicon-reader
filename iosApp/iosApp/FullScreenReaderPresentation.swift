import UIKit

/// Presents a reader (EPUB, comic, PDF, audiobook player) as a genuine full-screen screen,
/// dismissed by swiping in from the left edge (issue #176).
///
/// Every reader used to just `present()` a plain `UINavigationController`, which defaults to
/// UIKit's `.pageSheet` style — a partial-height "pullup" card with its own pull-down-to-dismiss
/// — plus an explicit Done button to close it. Neither matches how a book or audiobook player
/// should take over the screen, and the standard `UINavigationController` push/pop edge-swipe
/// (`interactivePopGestureRecognizer`) can't help here: it only works when there's a *previous*
/// view controller on the same stack to pop back to, and every reader presents a single view
/// controller of its own. This reimplements that same edge-swipe feel for a modal
/// `present()`/`dismiss()` pair instead.
enum FullScreenReaderPresentation {
    /// Wraps `content` in a `UINavigationController` presented full-screen — dismissed by
    /// swiping in from the left edge, plus a real native back button as a guaranteed fallback
    /// (issue #176 follow-up: on a real device the swipe works, but it's unreliable enough in
    /// the Simulator specifically — mouse-driven touches don't reproduce a true off-screen-edge
    /// touch the way a finger does — that a screen with *no* tap-to-exit control is a genuine
    /// dead end, confirmed live). Pass `hidesNavigationBar: true` only when `content` already
    /// draws its own back control as part of its actual content (the shared Compose
    /// `PlayerScreen`'s `BackPill`, currently the only such case) — every purely native reader
    /// needs this bar's back button since it has no equivalent of its own.
    static func wrap(_ content: UIViewController, hidesNavigationBar: Bool = false) -> UIViewController {
        FullScreenReaderNavigationController(rootViewController: content, hidesNavigationBar: hidesNavigationBar)
    }
}

private final class FullScreenReaderNavigationController: UINavigationController {
    private var interactor: EdgeSwipeDismissInteractor?
    private let hidesNavigationBar: Bool

    init(rootViewController: UIViewController, hidesNavigationBar: Bool) {
        self.hidesNavigationBar = hidesNavigationBar
        super.init(rootViewController: rootViewController)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        modalPresentationStyle = .fullScreen
        if hidesNavigationBar {
            setNavigationBarHidden(true, animated: false)
        } else if let root = viewControllers.first {
            root.navigationItem.leftBarButtonItem = UIBarButtonItem(
                image: UIImage(systemName: "chevron.backward"),
                style: .plain,
                target: self,
                action: #selector(closeTapped)
            )
        }

        let interactor = EdgeSwipeDismissInteractor(presentedViewController: self)
        self.interactor = interactor
        transitioningDelegate = interactor
    }

    @objc private func closeTapped() {
        dismiss(animated: true)
    }
}

/// Recognizes a left-edge pan and interactively dismisses the presented reader.
private final class EdgeSwipeDismissInteractor: NSObject, UIViewControllerTransitioningDelegate, UIGestureRecognizerDelegate {
    private weak var presentedViewController: UIViewController?
    private var interactionInProgress = false
    private let percentDriven = UIPercentDrivenInteractiveTransition()

    init(presentedViewController: UIViewController) {
        self.presentedViewController = presentedViewController
        super.init()

        let edgePan = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        edgePan.edges = .left
        // Without this, the presented content's own touch handling — Compose Multiplatform's
        // root view runs its own low-level pointer-input dispatch for the player screen; the
        // comic pager's UIPageViewController/zoom UIScrollView do the same via their own pan
        // recognizers — can claim the touch first and this edge-pan never even reaches
        // `.began`, since UIKit's default conflict resolution only lets one recognizer win a
        // touch sequence. Explicitly allowing simultaneous recognition is the standard fix.
        edgePan.delegate = self
        presentedViewController.view.addGestureRecognizer(edgePan)
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }

    @objc private func handlePan(_ gesture: UIScreenEdgePanGestureRecognizer) {
        guard let view = presentedViewController?.view else { return }
        let translation = gesture.translation(in: view)
        let progress = min(max(translation.x / view.bounds.width, 0), 1)

        switch gesture.state {
        case .began:
            interactionInProgress = true
            presentedViewController?.dismiss(animated: true)
        case .changed:
            percentDriven.update(progress)
        case .ended, .cancelled, .failed:
            interactionInProgress = false
            let isQuickFlick = gesture.velocity(in: view).x > 800
            if progress > 0.4 || isQuickFlick {
                percentDriven.finish()
            } else {
                percentDriven.cancel()
            }
        default:
            break
        }
    }

    func animationController(forDismissed dismissed: UIViewController) -> UIViewControllerAnimatedTransitioning? {
        EdgeSwipeDismissAnimator()
    }

    func interactionControllerForDismissal(using animator: UIViewControllerAnimatedTransitioning) -> UIViewControllerInteractiveTransitioning? {
        interactionInProgress ? percentDriven : nil
    }
}

/// Slides the presented reader off to the right — the direction a left-edge swipe implies —
/// instead of UIKit's default `.fullScreen` dismiss (an instant cut with no animation).
private final class EdgeSwipeDismissAnimator: NSObject, UIViewControllerAnimatedTransitioning {
    func transitionDuration(using transitionContext: UIViewControllerContextTransitioning?) -> TimeInterval {
        0.3
    }

    func animateTransition(using transitionContext: UIViewControllerContextTransitioning) {
        guard let fromView = transitionContext.view(forKey: .from) else {
            transitionContext.completeTransition(false)
            return
        }
        let container = transitionContext.containerView
        container.addSubview(fromView)

        UIView.animate(
            withDuration: transitionDuration(using: transitionContext),
            delay: 0,
            options: .curveEaseOut,
            animations: {
                fromView.frame = fromView.frame.offsetBy(dx: container.bounds.width, dy: 0)
            },
            completion: { finished in
                transitionContext.completeTransition(finished && !transitionContext.transitionWasCancelled)
            }
        )
    }
}
