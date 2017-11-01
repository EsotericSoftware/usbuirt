
package com.esotericsoftware.usbuirt;

import static com.esotericsoftware.usbuirt.Win.Kernel32.*;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary.StdCallCallback;
import com.sun.jna.win32.W32APIOptions;

/** @author Nathan Sweet */
public class UsbUirt {
	static public final int FORMAT_UUIRT = 0x0000;
	static public final int FORMAT_PRONTO = 0x0010;
	static public final int FORMAT_LEARN_FORCERAW = 0x0100;
	static public final int FORMAT_LEARN_FORCESTRUC = 0x0200;
	static public final int FORMAT_LEARN_FORCEFREQ = 0x0400;
	static public final int FORMAT_LEARN_FREQDETECT = 0x0800;

	static private final int ERROR_CONNECTION = 0x20000001;
	static private final int ERROR_COMMUNICATION = 0x20000002;
	static private final int ERROR_DRIVER_NOT_FOUND = 0x20000003;
	static private final int ERROR_INCOMPATIBLE_FIRMWARE = 0x20000004;
	static private final int ERROR_UNKNOWN = -1;

	static private final int CONFIG_LEDRX = 0x0001;
	static private final int CONFIG_LEDTX = 0x0002;
	static private final int CONFIG_LEGACYRX = 0x0004;

	static {
		new SharedLibraryLoader().load("uuirtdrv");
		Native.register(NativeLibrary.getInstance("uuirtdrv", W32APIOptions.DEFAULT_OPTIONS));
	}

	private Pointer handle;
	private boolean isLearning;
	private final Memory learnedCode = new Memory(32768);
	private final IntByReference abortLearning = new IntByReference();

	private final PUUCALLBACKPROC receive = new PUUCALLBACKPROC() {
		public void invoke (Pointer IREventStr, Pointer userData) {
			receive(IREventStr.getString(0));
		}
	};

	private final PLEARNCALLBACKPROC learn = new PLEARNCALLBACKPROC() {
		public void invoke (int progress, int sigQuality, NativeLong carrierFreq, Pointer userData) {
			learn(progress, sigQuality, carrierFreq.longValue());
		}
	};

	public int getDriverVersion () {
		int[] version = new int[1];
		if (!UUIRTGetDrvInfo(version)) return -1;
		return version[0];
	}

	public void connect () throws Exception {
		long result = UUIRTOpen();
		if (result == -1) {
			int error = Native.getLastError();
			switch (error) {
			case ERROR_DRIVER_NOT_FOUND:
				throw new Exception("Driver not found.");
			case ERROR_CONNECTION:
				throw new Exception("Unable to connect to device.");
			case ERROR_COMMUNICATION:
				throw new Exception("Unable to communicate with device.");
			case ERROR_INCOMPATIBLE_FIRMWARE:
				throw new Exception("Incompatible firmware.");
			}
			throw new Exception("Unknown error: " + error);
		}

		Pointer pointer = new Pointer(result);
		if (!UUIRTSetReceiveCallback(pointer, receive, null)) throw new Exception("Unable to set receive callback.");
		handle = pointer;
	}

	public boolean disconnect () {
		if (handle == null) return true;
		try {
			return UUIRTClose(handle);
		} finally {
			handle = null;
		}
	}

	public boolean isConnected () {
		return handle != null;
	}

	/** @return May be null. */
	public Info getInfo () {
		if (handle == null) return null;
		PUUINFO puuinfo = new PUUINFO();
		UUIRTGetUUIRTInfo(handle, puuinfo);
		Info info = new Info();
		info.firmwareVersion = (puuinfo.fwVersion >> 8) + "." + (puuinfo.fwVersion & 0xFF);
		info.protocolVersion = (puuinfo.protVersion >> 8) + "." + (puuinfo.protVersion & 0xFF);
		info.firmwareDate = puuinfo.fwDateMonth + "/" + puuinfo.fwDateDay + "/" + (puuinfo.fwDateYear + 2000);
		return info;
	}

	/** @return May be null. */
	public Config getConfig () {
		if (handle == null) return null;
		int[] flags = new int[1];
		UUIRTGetUUIRTConfig(handle, flags);
		return new Config(flags[0]);
	}

	public void setConfig (Config config) {
		if (handle == null) return;
		UUIRTSetUUIRTConfig(handle, config.flags);
	}

	/** Transmits the specified IR code.
	 * @param format See FORMAT_* constants.
	 * @param repeat Number of times to repeat the code. For a two-piece code the first piece is sent once followed by the second
	 *           piece repeat times.
	 * @param inactivityWaitTime Milliseconds since the last received IR activity to wait before sending the IR code. Normally pass
	 *           0.
	 * @param blockExecution If true, execution will be blocked until the IR code is sent. */
	public boolean transmit (String code, int format, int repeat, int inactivityWaitTime, boolean blockExecution) {
		if (handle == null) return false;
		Pointer doneEvent = null;
		if (blockExecution) doneEvent = CreateEvent(null, false, false, new WString("hUSBUIRTXAckEvent"));
		if (!UUIRTTransmitIR(handle, new WString(code), format, repeat, inactivityWaitTime, doneEvent, null, null)) return false;
		if (!blockExecution) return true;
		boolean success = WaitForSingleObject(doneEvent, 10 * 1000) == 0; // WAIT_OBJECT_0
		CloseHandle(doneEvent);
		return success;
	}

	/** Transmits the specified IR code with the format=FORMAT_PRONTO, repeat=1, inactivityWaitTime=0. */
	public boolean transmit (String code, boolean blockExecution) {
		return transmit(code, FORMAT_PRONTO, 1, 0, blockExecution);
	}

	/** Learns and returns a code in the specified format but only for the specified frequency or null if learning was aborted or
	 * failed. Blocks execution. */
	public String learn (int format, int forcedFrequency) {
		if (handle == null) return null;
		if (isLearning) return null;
		isLearning = true;
		try {
			abortLearning.setValue(0);
			if (!UUIRTLearnIR(handle, format, learnedCode, learn, null, abortLearning, forcedFrequency, null, null)) return null;
			return learnedCode.getString(0);
		} finally {
			isLearning = false;
		}
	}

	/** Learns and returns a code in the specified format or null if learning was aborted or failed. Blocks execution. */
	public synchronized String learn (int format) {
		return learn(format, 0);
	}

	/** Learns and returns a code in the Pront format or null if learning was aborted or failed. The Pronto format is the most
	 * reliable, though it generates the longest codes. Blocks execution.
	 * @return Empty string if learning was cancelled. */
	public synchronized String learn () {
		return learn(FORMAT_PRONTO, 0);
	}

	public void abortLearn () {
		abortLearning.setValue(1);
	}

	protected void receive (String code) {
	}

	protected void learn (int progress, int signalQuality, long carrierFrequency) {
	}

	static private native boolean UUIRTGetDrvInfo (int[] puDrvVersion);

	static private native long UUIRTOpen ();

	static private native boolean UUIRTClose (Pointer hHandle);

	static private native boolean UUIRTGetUUIRTInfo (Pointer hHandle, PUUINFO puuInfo);

	static private native boolean UUIRTGetUUIRTConfig (Pointer hHandle, int[] puConfig);

	static private native boolean UUIRTSetUUIRTConfig (Pointer hHandle, int uConfig);

	static private native boolean UUIRTSetReceiveCallback (Pointer hHandle, PUUCALLBACKPROC receiveProc, Pointer userData);

	static private native boolean UUIRTTransmitIR (Pointer hHandle, WString IRCode, int codeFormat, int repeatCount,
		int inactivityWaitTime, Pointer hEvent, Pointer reserved0, Pointer reserved1);

	static private native boolean UUIRTLearnIR (Pointer hHandle, int codeFormat, Pointer IRCode, PLEARNCALLBACKPROC progressProc,
		Pointer userData, IntByReference pAbort, int param1, Pointer reserved0, Pointer reserved1);

	static private interface PUUCALLBACKPROC extends StdCallCallback {
		void invoke (Pointer IREventStr, Pointer userData);
	}

	static private interface PLEARNCALLBACKPROC extends StdCallCallback {
		void invoke (int progress, int sigQuality, NativeLong carrierFreq, Pointer userData);
	}

	static public class PUUINFO extends Structure {
		public int fwVersion;
		public int protVersion;
		public byte fwDateDay;
		public byte fwDateMonth;
		public byte fwDateYear;

		protected List getFieldOrder () {
			return Arrays.asList(new String[] {"fwVersion", "protVersion", "fwDateDay", "fwDateMonth", "fwDateYear"});
		}
	}

	static public class Info {
		public String firmwareVersion, protocolVersion, firmwareDate;
	}

	static public class Config {
		int flags;

		Config (int flags) {
			this.flags = flags;
		}

		public boolean getReceiveLED () {
			return (flags & CONFIG_LEDRX) != 0;
		}

		public void setReceiveLED (boolean receiveLED) {
			if (receiveLED)
				flags |= CONFIG_LEDRX;
			else
				flags &= ~CONFIG_LEDRX;
		}

		public boolean getTransmitLED () {
			return (flags & CONFIG_LEDTX) != 0;
		}

		public void setTransmitLED (boolean receiveLED) {
			if (receiveLED)
				flags |= CONFIG_LEDTX;
			else
				flags &= ~CONFIG_LEDTX;
		}

		public boolean getLegacyReceive () {
			return (flags & CONFIG_LEGACYRX) != 0;
		}

		public void setLegacyReceive (boolean receiveLED) {
			if (receiveLED)
				flags |= CONFIG_LEGACYRX;
			else
				flags &= ~CONFIG_LEGACYRX;
		}
	}

	static public void main (String[] args) throws Exception {
		final UsbUirt uirt = new UsbUirt() {
			protected void learn (int progress, int signalQuality, long carrierFrequency) {
				System.out.println("Learning: " + progress + ", " + signalQuality + ", " + carrierFrequency);
			}

			protected void receive (String code) {
				System.out.println("Received: " + code);
			}
		};

		System.out.println("Driver version: " + uirt.getDriverVersion() + "\n");

		uirt.connect();
		System.out.println("Connected: " + uirt.isConnected() + "\n");

		Info info = uirt.getInfo();
		System.out.println("Firmware version: " + info.firmwareVersion);
		System.out.println("Firmware date: " + info.firmwareDate);
		System.out.println("Protocol version: " + info.protocolVersion + "\n");

		Config config = uirt.getConfig();
		System.out.println("Receive LED: " + config.getReceiveLED());
		System.out.println("Transmit LED: " + config.getTransmitLED());
		System.out.println("Legacy receive: " + config.getLegacyReceive() + "\n");

		uirt.setConfig(config);

		System.out.println("Transmit blocking...");
		uirt.transmit(
			"0000 006F 0000 0032 0081 0042 0010 0011 0010 0031 0010 0011 0010 0011 0010 0011 0010 0011 0010 0011 0010 0011 0010 "
				+ "0011 0010 0011 0010 0011 0010 0011 0010 0011 0010 0031 0010 0011 0010 0011 0010 0011 0010 0011 0010 0011 0010 "
				+ "0011 0010 0011 0010 0011 0010 0011 0010 0031 0010 0011 0010 0011 0010 0011 0010 0011 0010 0011 0010 0011 0010 "
				+ "0011 0010 0011 0010 0011 0010 0011 0010 0011 0010 0011 0010 0031 0010 0011 0010 0011 0010 0011 0010 0011 0010 "
				+ "0011 0010 0011 0010 0011 0010 0031 0010 0011 0010 0011 0010 0031 0010 0ADC",
			true);
		System.out.println("Done.\n");

		System.out.println("Learning...");
		String code = uirt.learn();
		System.out.println("Learned: " + code);

		Thread.sleep(500);

		System.out.println("\nTransmit blocking...");
		long s = System.nanoTime();
		uirt.transmit(code, true);
		long e = System.nanoTime();
		System.out.println("Done. " + (e - s) / 1e6 + "\n");

		System.out.println("Transmit non-blocking...");
		s = System.nanoTime();
		uirt.transmit(code, false);
		e = System.nanoTime();
		System.out.println("Done. " + (e - s) / 1e6 + "ms\n");

		Thread.sleep(500);

		new Thread() {
			public void run () {
				System.out.println("Learning...");
				String code = uirt.learn();
				System.out.println("Learned: " + code + "ms\n");
			}
		}.start();

		Thread.sleep(2000);

		System.out.println("Aborting.");
		uirt.abortLearn();

		Thread.sleep(500);

		System.out.println("Idle...");

		Thread.sleep(5000);

		System.out.println("\nDisconnecting...");
		uirt.disconnect();
		System.out.println("Connected: " + uirt.isConnected());
	}
}
