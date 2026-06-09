type StreamBufferOptions = {
  intervalMs: number;
  mergeChunk: (previous: string, nextChunk: string) => string;
  isActive: () => boolean;
  onFlush: (content: string) => void;
};

export function createStreamBuffer({
  intervalMs,
  mergeChunk,
  isActive,
  onFlush,
}: StreamBufferOptions) {
  let content = "";
  let timer: number | null = null;

  const clearTimer = () => {
    if (timer !== null) {
      window.clearTimeout(timer);
      timer = null;
    }
  };

  const flush = () => {
    clearTimer();
    if (!isActive()) {
      return;
    }
    onFlush(content);
  };

  const append = (chunk: string) => {
    content = mergeChunk(content, chunk);
    if (timer !== null) {
      return;
    }
    timer = window.setTimeout(flush, intervalMs);
  };

  const cancel = () => {
    clearTimer();
  };

  const getContent = () => content;

  return {
    append,
    flush,
    cancel,
    getContent,
  };
}
