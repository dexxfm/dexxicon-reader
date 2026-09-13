import UIKit

/// Presents a reader (EPUB, comic, PDF, audiobook player) as a genuine full-screen screen,
/// dismissed by swiping in from either edge (issue #176).
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
    /// swiping in from either edge, plus a real native back button as a guaranteed fallback
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
        NSLog("[FullScreenReader] viewDidLoad: hidesNavigationBar=\(hidesNavigationBar) viewControllers=\(viewControllers)")
        if hidesNavigationBar {
            setNavigationBarHidden(true, animated: false)
        } else if let root = viewControllers.first {
            root.navigationItem.leftBarButtonItem = UIBarButtonItem(
                image: UIImage(systemName: "chevron.backward"),
                style: .plain,
                target: self,
                action: #selector(closeTapped)
            )
            NSLog("[FullScreenReader] back button installed on \(root)")
        } else {
            NSLog("[FullScreenReader] no root view controller to install a back button on!")
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
///
/// A plain `UIPanGestureRecognizer` restricted to starting near the left edge, not
/// `UIScreenEdgePanGestureRecognizer` — two real, confirmed problems with the specialized
/// recognizer in a row (never reaching `.began` at all, even after explicitly allowing
/// simultaneous recognition with whatever's underneath it) point at its own internal,
/// undocumented edge hot-zone being too narrow/fussy to trigger reliably here, not just a
/// gesture-conflict issue. A regular pan recognizer gated by `shouldReceive touch:` (checking
/// the touch's own starting point, not relying on the recognizer's built-in edge detection)
/// gives full control over how wide that hot-zone actually is.
private final class EdgeSwipeDismissInteractor: NSObject, UIViewControllerTransitioningDelegate, UIGestureRecognizerDelegate {
    private weak var presentedViewController: UIViewController?
    private var interactionInProgress = false
    private let percentDriven = UIPercentDrivenInteractiveTransition()
    private let edgeWidth: CGFloat = 56
    /// +1 for a swipe that started at the left edge (dismiss slides the screen off to the
    /// right, dragging the same way the finger moved); -1 for the right edge (slides off to
    /// the left). Set at `.began`; left alone for a non-interactive dismiss (e.g. the back
    /// button), which just reuses whichever direction was last recorded.
    private var dismissDirection: CGFloat = 1

    init(presentedViewController: UIViewController) {
        self.presentedViewController = presentedViewController
        super.init()

        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        pan.delegate = self
        presentedViewController.view.addGestureRecognizer(pan)
    }

    /// Only let the gesture start tracking at all if the touch itself began within [edgeWidth]
    /// of the left *or* right edge — this is what makes it an "edge" gesture despite using a
    /// plain pan recognizer, which otherwise has no edge concept. Both edges dismiss (issue
    /// #176 follow-up: users expect either side to work, not just one).
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard let view = presentedViewController?.view else { return false }
        let x = touch.location(in: view).x
        return x <= edgeWidth || x >= view.bounds.width - edgeWidth
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        guard let view = presentedViewController?.view else { return }
        let translation = gesture.translation(in: view)

        if gesture.state == .began {
            // Where this touch started (translation is 0 at .began, but reading it here keeps
            // this self-contained rather than needing a separate touch-down callback): a
            // left-edge swipe drags rightward (positive x), a right-edge swipe drags leftward
            // (negative x) — dismissDirection normalizes either into a positive "progress".
            let startX = gesture.location(in: view).x - translation.x
            dismissDirection = startX <= edgeWidth ? 1 : -1
        }

        let signedTranslation = translation.x * dismissDirection
        let progress = min(max(signedTranslation / view.bounds.width, 0), 1)

        switch gesture.state {
        case .began:
            interactionInProgress = true
            presentedViewController?.dismiss(animated: true)
        case .changed:
            percentDriven.update(progress)
        case .ended, .cancelled, .failed:
            interactionInProgress = false
            let isQuickFlick = gesture.velocity(in: view).x * dismissDirection > 500
            if progress > 0.35 || isQuickFlick {
                percentDriven.finish()
            } else {
                percentDriven.cancel()
            }
        default:
            break
        }
    }

    func animationController(forDismissed dismissed: UIViewController) -> UIViewControllerAnimatedTransitioning? {
        EdgeSwipeDismissAnimator(direction: dismissDirection)
    }

    func interactionControllerForDismissal(using animator: UIViewControllerAnimatedTransitioning) -> UIViewControllerInteractiveTransitioning? {
        interactionInProgress ? percentDriven : nil
    }
}

/// Slides the presented reader off in [direction] (+1 right, -1 left) — the way the swipe that
/// triggered it was dragging — instead of UIKit's default `.fullScreen` dismiss (an instant cut
/// with no animation).
private final class EdgeSwipeDismissAnimator: NSObject, UIViewControllerAnimatedTransitioning {
    private let direction: CGFloat

    init(direction: CGFloat) {
        self.direction = direction
    }

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
                fromView.frame = fromView.frame.offsetBy(dx: container.bounds.width * self.direction, dy: 0)
            },
            completion: { finished in
                transitionContext.completeTransition(finished && !transitionContext.transitionWasCancelled)
            }
        )
    }
}
