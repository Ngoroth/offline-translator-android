public final class ProcessHangFixture {
    public static void main(String[] arguments) throws InterruptedException {
        if (arguments.length > 0 && arguments[0].equals("output")) {
            for (int index = 0; index < 128; index++) System.out.println("x".repeat(65536));
        } else {
            Thread.sleep(30000);
        }
    }
}
