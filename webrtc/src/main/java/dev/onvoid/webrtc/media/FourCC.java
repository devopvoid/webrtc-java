/*
 * Copyright 2019 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.onvoid.webrtc.media;

/**
 * "Four character code" (4CC) enumeration mainly used for conversion.
 * 
 * @author Alex Andres
 */
public enum FourCC {

	// Primary RGB formats
	
	/** Uncompressed 32-bit ARGB */
	ARGB("ARGB"),
	/** Uncompressed 32-bit BGRA */
	BGRA("BGRA"),
	/** Uncompressed 32-bit ABGR */
	ABGR("ABGR"),
	/** Uncompressed 32-bit RGBA */
	RGBA("RGBA"),
	/** Uncompressed 24-bit BGR */
	BG24("24BG"),
	
	// Primary YUV formats
	
	/** Planar 4:2:0 YUV */
	I420("I420"),
	/** Planar 4:2:0 YUV with interleaved VU plane */
	NV21("NV21"),
	/** Planar 4:2:0 YUV with interleaved UV plane */
	NV12("NV12"),
	/** Packed 4:2:2 YUV */
	YUY2("YUY2"),
	/** Packed 4:2:2 YUV */
	UYVY("UYVY");
	
	
	/**
     * Integer FourCC value.
     */
    private final int value;
    
	
	private FourCC(String fourCC) {
		int val = 0;
		
		for (int i = 0; i < 4; i++) {
			val <<= 8;
			val |= fourCC.charAt(3 - i);
		}
		
		this.value = val;
	}
	
	/**
     * Return the FourCC as integer value.
     *
     * @return FourCC integer.
     */
    public int value() {
        return value;
    }
	
}
