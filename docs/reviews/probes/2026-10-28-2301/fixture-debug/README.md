此失败为隔离夹具自身的 Continuation 上下文错误：Compose restricted suspension 要求 EmptyCoroutineContext，最初误传 runBlocking 的上下文。未执行到目标手势分支，不能作为应用缺陷证据或最终失败计数。随后仅修正夹具的 Continuation 转接，再运行生产协程。
